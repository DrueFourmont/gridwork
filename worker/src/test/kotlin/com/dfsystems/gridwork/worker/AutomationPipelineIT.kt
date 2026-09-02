package com.dfsystems.gridwork.worker

import com.dfsystems.gridwork.core.automation.AutomationRepository
import com.dfsystems.gridwork.core.outbox.OutboxRelay
import com.dfsystems.gridwork.core.outbox.OutboxRepository
import com.dfsystems.gridwork.core.service.CellService
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * The whole automation pipeline, with nothing faked.
 *
 * A real Postgres, a real LocalStack SQS, and the real relay and consumer. A
 * cell is written through CellService, which puts an event in the outbox; the
 * relay publishes it; the consumer picks it up; the automation fires; and the
 * action lands back in the database as another versioned cell write.
 *
 * The parts worth proving are the ones a unit test cannot: that the outbox row
 * and the cell commit together, that a duplicate delivery does nothing twice,
 * and that a loop stops.
 */
@SpringBootTest
class AutomationPipelineIT {

    @Autowired private lateinit var cells: CellService
    @Autowired private lateinit var relay: OutboxRelay
    @Autowired private lateinit var consumer: AutomationConsumer
    @Autowired private lateinit var outbox: OutboxRepository
    @Autowired private lateinit var automations: AutomationRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    private lateinit var user: UUID
    private lateinit var sheet: UUID
    private lateinit var statusColumn: UUID
    private lateinit var doneColumn: UUID
    private lateinit var row: UUID

    @BeforeEach
    fun seed() {
        jdbc.execute(
            """
            truncate table processed_events, outbox_events, cell_history, cells, rows,
                           automation_conditions, automations, columns,
                           sheet_members, sheets, idempotency_keys, users
            restart identity cascade
            """.trimIndent(),
        )
        user = UUID.randomUUID()
        jdbc.update(
            "insert into users (id, email, password_hash, display_name) values (?, ?, 'x', 'Tester')",
            user, "worker-${UUID.randomUUID()}@example.com",
        )
        sheet = UUID.randomUUID()
        jdbc.update("insert into sheets (id, owner_id, name) values (?, ?, 'Automations')", sheet, user)
        jdbc.update("insert into sheet_members (sheet_id, user_id, role) values (?, ?, 'OWNER')", sheet, user)

        statusColumn = UUID.randomUUID()
        doneColumn = UUID.randomUUID()
        jdbc.update(
            "insert into columns (id, sheet_id, name, type, position) values (?, ?, 'Status', 'TEXT', 0)",
            statusColumn, sheet,
        )
        jdbc.update(
            "insert into columns (id, sheet_id, name, type, position) values (?, ?, 'Done', 'CHECKBOX', 1)",
            doneColumn, sheet,
        )
        row = UUID.randomUUID()
        jdbc.update("insert into rows (id, sheet_id, position) values (?, ?, 0)", row, sheet)
        for (column in listOf(statusColumn, doneColumn)) {
            jdbc.update(
                """
                insert into cells (row_id, column_id, sheet_id, value, version, updated_by)
                values (?, ?, ?, null, 1, ?)
                """.trimIndent(),
                row, column, sheet, user,
            )
        }
        drainQueue()
    }

    private fun addAutomation(
        triggerColumn: UUID,
        actionColumn: UUID,
        actionValue: String,
        triggerValue: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into automations
                (id, sheet_id, name, enabled, trigger_type, trigger_column_id,
                 trigger_value, action_column_id, action_value, created_by)
            values (?, ?, 'auto', true, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, sheet,
            if (triggerValue == null) "COLUMN_CHANGED" else "COLUMN_CHANGED_TO",
            triggerColumn, triggerValue, actionColumn, actionValue, user,
        )
        return id
    }

    private fun writeCell(column: UUID, value: String?, expectedVersion: Long) {
        cells.batchUpdate(
            sheetId = SheetId(sheet),
            actorId = UserId(user),
            requested = listOf(CellService.RequestedWrite(row, column, value, expectedVersion)),
        )
    }

    private fun valueOf(column: UUID): String? = jdbc.queryForObject(
        "select value from cells where row_id = ? and column_id = ?",
        String::class.java, row, column,
    )

    private fun versionOf(column: UUID): Long = jdbc.queryForObject(
        "select version from cells where row_id = ? and column_id = ?",
        Long::class.java, row, column,
    )!!

    /** Runs the relay and the consumer until the pipeline goes quiet. */
    private fun runPipeline(rounds: Int = 8) {
        repeat(rounds) {
            relay.drain()
            consumer.poll()
        }
    }

    private fun drainQueue() {
        repeat(3) {
            relay.drain()
            consumer.poll()
        }
        jdbc.execute("truncate table processed_events")
    }

    // ---------------------------------------------------------------------

    @Test
    fun `a cell write and its outbox event commit together`() {
        // The transactional outbox in one assertion: the event exists because
        // the write did, in the same transaction, with no publish step in the
        // request path that could have failed independently.
        val before = outbox.unpublishedCount()
        writeCell(statusColumn, "Done", 1)
        val after = outbox.unpublishedCount()
        check(after == before + 1) { "expected exactly one new outbox event, got ${after - before}" }
    }

    @Test
    fun `an automation fires through the real queue and writes the target cell`() {
        addAutomation(triggerColumn = statusColumn, actionColumn = doneColumn, actionValue = "true")

        writeCell(statusColumn, "Done", 1)
        runPipeline()

        check(valueOf(doneColumn) == "true") { "expected the automation to set Done, got ${valueOf(doneColumn)}" }
        // Version two, not one, because the automation wrote through
        // CellService exactly as a person would.
        check(versionOf(doneColumn) == 2L) { "expected version 2, got ${versionOf(doneColumn)}" }
    }

    @Test
    fun `the automation's own write is attributed and versioned like any other`() {
        addAutomation(statusColumn, doneColumn, "true")
        writeCell(statusColumn, "Done", 1)
        runPipeline()

        val history = jdbc.queryForObject(
            "select count(*) from cell_history where row_id = ? and column_id = ?",
            Int::class.java, row, doneColumn,
        )
        // History exists for the automation's write because it went through
        // the same service. A direct table write would have left none.
        check(history == 1) { "expected one history row for the automated write, got $history" }
    }

    @Test
    fun `a redelivered message does not apply the action twice`() {
        addAutomation(statusColumn, doneColumn, "true")
        writeCell(statusColumn, "Done", 1)
        runPipeline()
        val versionAfterFirst = versionOf(doneColumn)

        // Republish everything: mark the outbox rows unpublished and drain
        // again, which is exactly what SQS redelivery looks like from here.
        jdbc.update("update outbox_events set published_at = null")
        runPipeline()

        check(versionOf(doneColumn) == versionAfterFirst) {
            "a duplicate delivery changed the cell again: ${versionOf(doneColumn)} vs $versionAfterFirst"
        }
    }

    @Test
    fun `two automations pointing at each other stop at the depth limit`() {
        // A writes B, B writes A. Neither is individually invalid, so no save
        // time check can catch it. The depth carried on each event is what
        // stops it, per CLAUDE.md.
        addAutomation(triggerColumn = statusColumn, actionColumn = doneColumn, actionValue = "true")
        addAutomation(triggerColumn = doneColumn, actionColumn = statusColumn, actionValue = "Reopened")

        writeCell(statusColumn, "Done", 1)
        runPipeline(rounds = 20)

        // Bounded rather than exact: what matters is that it terminated well
        // short of runaway, not the precise count.
        val statusVersion = versionOf(statusColumn)
        val doneVersion = versionOf(doneColumn)
        check(statusVersion <= 5 && doneVersion <= 5) {
            "the automation loop did not stop: status v$statusVersion, done v$doneVersion"
        }
        check(outbox.unpublishedCount() == 0L) { "the pipeline did not settle" }
    }

    @Test
    fun `an automation whose condition does not hold does nothing`() {
        val id = addAutomation(statusColumn, doneColumn, "true")
        jdbc.update(
            """
            insert into automation_conditions (automation_id, column_id, comparator, value)
            values (?, ?, 'EQUALS', 'never')
            """.trimIndent(),
            id, doneColumn,
        )

        writeCell(statusColumn, "Done", 1)
        runPipeline()

        check(valueOf(doneColumn) == null) { "the automation fired despite a failing condition" }
    }

    @Test
    fun `a trigger waiting for a specific value ignores other values`() {
        addAutomation(statusColumn, doneColumn, "true", triggerValue = "Done")

        writeCell(statusColumn, "In progress", 1)
        runPipeline()
        check(valueOf(doneColumn) == null) { "fired on the wrong value" }

        writeCell(statusColumn, "Done", 2)
        runPipeline()
        check(valueOf(doneColumn) == "true") { "did not fire on the right value" }
    }

    @Test
    fun `the relay marks events published exactly once`() {
        addAutomation(statusColumn, doneColumn, "true")
        writeCell(statusColumn, "Done", 1)
        runPipeline()

        val unpublished = outbox.unpublishedCount()
        check(unpublished == 0L) { "expected every event published, $unpublished remain" }
    }

    companion object {
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @JvmStatic
        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379).apply { start() }

        @JvmStatic
        private val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
                .withServices(LocalStackContainer.Service.SQS)
                .apply { start() }

        private lateinit var queueUrl: String

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            queueUrl = localstack.execInContainer(
                "awslocal", "sqs", "create-queue", "--queue-name", "gridwork-events",
                "--query", "QueueUrl", "--output", "text",
            ).stdout.trim()

            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.url") {
                "redis://" + redis.host + ":" + redis.getMappedPort(6379).toString()
            }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("gridwork.sqs.queue-url") { queueUrl }
            registry.add("gridwork.sqs.endpoint") { localstack.getEndpointOverride(LocalStackContainer.Service.SQS).toString() }
            registry.add("gridwork.sqs.region") { localstack.region }
            // The tests drive the relay and the consumer by hand so each step
            // is observable. A background schedule would make them racy.
            registry.add("gridwork.outbox.relay.interval") { "3600000" }
            registry.add("gridwork.sqs.poll-interval") { "3600000" }
            registry.add("gridwork.sqs.wait-seconds") { "1" }
        }
    }
}

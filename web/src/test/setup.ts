import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'

/**
 * jsdom has no layout engine. Every element measures zero by zero, and
 * ResizeObserver does not exist at all.
 *
 * That is fatal for a virtualised list: TanStack Virtual asks the scroll
 * container how tall it is, is told zero, and correctly concludes that no rows
 * are visible. The component is not broken; the environment simply cannot
 * measure. So the test environment is given just enough fake layout for
 * measurement to return something sensible.
 *
 * This is why the 60 fps budget is measured in a real browser rather than
 * here. jsdom can tell you the DOM stays small; only Chrome can tell you the
 * frames arrive on time.
 */

/**
 * A ResizeObserver that actually reports, once, on observe.
 *
 * An inert stub is not enough. TanStack Virtual learns the size of its scroll
 * container through this callback, so a stub that never fires leaves the
 * viewport measured as zero high, and the virtualiser correctly renders no
 * rows at all.
 */
class ResizeObserverStub implements ResizeObserver {
  constructor(private readonly callback: ResizeObserverCallback) {}

  observe(target: Element): void {
    const rect = target.getBoundingClientRect()
    const entry = {
      target,
      contentRect: rect,
      borderBoxSize: [{ inlineSize: rect.width, blockSize: rect.height }],
      contentBoxSize: [{ inlineSize: rect.width, blockSize: rect.height }],
      devicePixelContentBoxSize: [{ inlineSize: rect.width, blockSize: rect.height }],
    } as unknown as ResizeObserverEntry
    this.callback([entry], this)
  }

  unobserve(): void {}
  disconnect(): void {}
}

vi.stubGlobal('ResizeObserver', ResizeObserverStub)

const VIEWPORT_HEIGHT = 600
const VIEWPORT_WIDTH = 1200

Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
  configurable: true,
  get(this: HTMLElement) {
    return this.getAttribute('data-testid') === 'grid-scroll' ? VIEWPORT_HEIGHT : 0
  },
})

Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
  configurable: true,
  get: () => VIEWPORT_WIDTH,
})

HTMLElement.prototype.getBoundingClientRect = function getBoundingClientRect(this: HTMLElement) {
  const height = this.getAttribute('data-testid') === 'grid-scroll' ? VIEWPORT_HEIGHT : 32
  return {
    x: 0,
    y: 0,
    top: 0,
    left: 0,
    right: VIEWPORT_WIDTH,
    bottom: height,
    width: VIEWPORT_WIDTH,
    height,
    toJSON: () => ({}),
  }
}

afterEach(() => {
  vi.restoreAllMocks()
})

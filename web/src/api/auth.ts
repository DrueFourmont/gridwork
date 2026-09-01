import { request } from './client'
import { loginResponseSchema, registerResponseSchema, type LoginResponse } from './types'

export function login(email: string, password: string): Promise<LoginResponse> {
  return request('/v1/auth/login', loginResponseSchema, {
    method: 'POST',
    body: { email, password },
  })
}

export function register(email: string, password: string, displayName: string) {
  return request('/v1/auth/register', registerResponseSchema, {
    method: 'POST',
    body: { email, password, displayName },
  })
}

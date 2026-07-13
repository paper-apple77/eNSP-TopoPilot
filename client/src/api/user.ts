import request from './request'

/** 用户相关 API */

export function login(email: string, password: string) {
  return request.post('/user/login', { email, password })
}

export function register(email: string, password: string) {
  return request.post('/user/register', { email, password })
}

export function logout() {
  return request.post('/user/logout')
}

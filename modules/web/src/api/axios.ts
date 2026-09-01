import axios from 'axios'

/** @type {string} localStorage保存自定义阅读http服务接口的键值 */
export const baseURL_localStorage_key = 'remoteUrl'
export const webServiceToken_sessionStorage_key = 'webServiceToken'
export const webServiceAuthRequiredEvent = 'legado-web-auth-required'
const SECOND = 1000

const ajax = axios.create({
  baseURL:
    import.meta.env.VITE_API ||
    localStorage.getItem(baseURL_localStorage_key) ||
    location.origin,
  timeout: 120 * SECOND,
  withCredentials: true,
})

export const getWebServiceToken = (): string =>
  sessionStorage.getItem(webServiceToken_sessionStorage_key) || ''

export const setWebServiceToken = (token: string) =>
  sessionStorage.setItem(webServiceToken_sessionStorage_key, token)

export const clearWebServiceToken = () =>
  sessionStorage.removeItem(webServiceToken_sessionStorage_key)

export const getWebServiceAuthHeaders = (): Record<string, string> => {
  const token = getWebServiceToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

ajax.interceptors.request.use(config => {
  const token = getWebServiceToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  config.withCredentials = true
  return config
})

export default ajax

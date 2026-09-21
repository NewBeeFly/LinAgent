/** 前端身份（env 可覆盖；本期无登录，401 时提示检查配置） */
export const TENANT_ID = (import.meta.env.VITE_TENANT_ID as string | undefined) ?? 'default'
export const USER_ID = (import.meta.env.VITE_USER_ID as string | undefined) ?? 'linmj'

export const identityHeaders = (): Record<string, string> => ({
  'x-tenant-id': TENANT_ID,
  'x-user-id': USER_ID,
})

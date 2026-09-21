/** 类型化 API 错误：替代裸 "HTTP 404"，404/401 分支由此无感处理（spec §7.2） */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }

  get unauthorized(): boolean {
    return this.status === 401
  }

  get notFound(): boolean {
    return this.status === 404
  }

  /** 从 fetch Response 构造：优先取后端 JSON {message}，回退 HTTP 状态描述 */
  static async from(resp: Response): Promise<ApiError> {
    let message = `HTTP ${resp.status}`
    try {
      const body = (await resp.json()) as { message?: string }
      if (body?.message) message = body.message
    } catch {
      /* 非 JSON 体保持回退 */
    }
    return new ApiError(resp.status, message)
  }
}

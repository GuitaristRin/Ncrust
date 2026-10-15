namespace Ncrust.Core.Auth
{
    /// <summary>
    /// 一段会话 cookie 的有效性判定。三态而不是 bool：网络异常必须与「明确无效」分开 ——
    /// 断网时把用户登出，比暂时显示一个错误要糟得多。
    /// </summary>
    public enum SessionValidation
    {
        /// <summary>账户接口确认这段 cookie 仍能读到账号资料。</summary>
        Valid,

        /// <summary>明确无效：没有非空 <c>MUSIC_U</c>，或账户接口返回的 account / profile 都是 null。</summary>
        Invalid,

        /// <summary>判不了（网络异常 / 超时 / 非 2xx / 响应不是 JSON）：调用方应保持现状。</summary>
        Unknown,
    }
}

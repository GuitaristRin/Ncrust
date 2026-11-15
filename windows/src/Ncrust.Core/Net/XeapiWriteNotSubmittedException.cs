using System;

namespace Ncrust.Core.Net
{
    /// <summary>
    /// xeapi 请求在发出前就被拦下（缺状态 / 公钥 / 反作弊 token）。服务端确定没收到，
    /// 上层只需保留待同步，不必担心重复写入。
    /// </summary>
    public sealed class XeapiWriteNotSubmittedException : Exception
    {
        public XeapiWriteNotSubmittedException(string message)
            : base(message)
        {
        }
    }
}

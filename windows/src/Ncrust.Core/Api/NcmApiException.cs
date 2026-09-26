using System;

namespace Ncrust.Core.Api
{
    /// <summary>业务码非 200 / 响应结构缺失时抛出（对应 Android 各 API 里的 <c>throw Exception</c>）。</summary>
    public sealed class NcmApiException : Exception
    {
        public NcmApiException(string message)
            : base(message)
        {
        }

        public NcmApiException(string message, Exception inner)
            : base(message, inner)
        {
        }
    }
}

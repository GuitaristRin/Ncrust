using System.Runtime.CompilerServices;

// 单元测试要直接验证内部实现（纯托管 AES-GCM、随机字节辅助），
// 这些类型不适合作为 Ncrust.Core 的公开 API 暴露出去。
[assembly: InternalsVisibleTo("Ncrust.Core.Tests")]

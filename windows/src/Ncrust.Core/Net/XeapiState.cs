using System;
using System.Globalization;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net.Crypto;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Net
{
    /// <summary>
    /// xeapi 的跨请求状态（协议 §2–§3.4）：
    ///
    /// - <see cref="DeviceId"/>：首次生成 32 位小写 hex 后落设置存储，**之后永不更换**。
    ///   每次请求换 deviceId 正是风控画像里「非真实设备」的特征，所以这里没有任何重置入口。
    /// - 公钥状态（publicKey / version / sk / nextUpdateTime）：落设置存储，跨进程复用。
    /// - <see cref="Session"/>：<c>x-encr-ssid</c> / <c>x-encr-sskey</c>，只存内存（进程级）。
    ///
    /// 公钥刷新的并发由内部信号量收口：同时有 N 个请求发现公钥过期时只发一次公钥请求，
    /// 其余等它落地后复用。
    /// </summary>
    public sealed class XeapiState
    {
        public const string DeviceIdKey = "xeapi_device_id";
        public const string PublicKeyKey = "xeapi_public_key";
        public const string PublicKeyVersionKey = "xeapi_public_key_version";
        public const string PublicKeySkKey = "xeapi_public_key_sk";
        public const string NextUpdateTimeKey = "xeapi_public_key_next_update";

        private readonly ISettingsStore _settings;
        private readonly SemaphoreSlim _refreshLock = new SemaphoreSlim(1, 1);

        private XeapiKeyState? _keyState;
        private long _nextUpdateTime;
        private long _refreshGeneration;

        public XeapiState(ISettingsStore settings)
        {
            _settings = settings ?? throw new ArgumentNullException(nameof(settings));
            DeviceId = LoadOrCreateDeviceId(settings);
            _keyState = LoadKeyState(settings);
            _nextUpdateTime = _keyState?.NextUpdateTime ?? 0;
        }

        /// <summary>持久化的设备 id（32 位小写 hex）。</summary>
        public string DeviceId { get; }

        /// <summary>会话模式所需的两个响应头；<c>null</c> 表示无会话（用随机动态密钥）。</summary>
        public XeapiSession? Session { get; set; }

        /// <summary>
        /// 取可用的公钥状态。<paramref name="forceRefresh"/> 为 true 时（业务响应解不开）
        /// 即使缓存看起来新鲜也重取一次，并把旧 <c>version</c> 交给 <paramref name="fetch"/>
        /// 填进 <c>currentKeyVersion</c>。
        /// </summary>
        public async Task<XeapiKeyState?> EnsureKeyStateAsync(
            Func<string?, CancellationToken, Task<XeapiKeyState?>> fetch,
            bool forceRefresh,
            CancellationToken cancellationToken = default)
        {
            if (fetch == null)
            {
                throw new ArgumentNullException(nameof(fetch));
            }

            var cached = GetFreshKeyState();
            if (!forceRefresh && cached != null)
            {
                return cached;
            }

            var generation = Interlocked.Read(ref _refreshGeneration);
            await _refreshLock.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                cached = GetFreshKeyState();
                if (!forceRefresh && cached != null)
                {
                    return cached;
                }

                // 排队期间已经有别的请求刷过公钥了：直接复用，别再打一次。
                if (forceRefresh && Interlocked.Read(ref _refreshGeneration) != generation)
                {
                    return _keyState;
                }

                var state = await fetch(_keyState?.Version, cancellationToken).ConfigureAwait(false);
                if (state != null)
                {
                    Save(state);
                    Interlocked.Increment(ref _refreshGeneration);
                    return state;
                }

                return _keyState;
            }
            finally
            {
                _refreshLock.Release();
            }
        }

        /// <summary>丢弃会话（业务响应解不开时用；下次请求回到随机动态密钥，服务端会重新下发会话）。</summary>
        public void ClearSession() => Session = null;

        /// <summary>
        /// 解析公钥接口解密后的 JSON。缺 <c>sk</c> 视为失败（协议 §2）——没有它 <c>S</c>
        /// 就缺一半明文，服务端不会认。
        /// </summary>
        public static XeapiKeyState? ParseKeyState(string json)
        {
            if (string.IsNullOrEmpty(json))
            {
                return null;
            }

            JsonValue value;
            try
            {
                value = JsonValue.Parse(json);
            }
            catch (JsonParseException)
            {
                return null;
            }

            var publicKey = value.GetString("publicKey");
            var sk = value.GetString("sk");
            if (string.IsNullOrEmpty(publicKey) || string.IsNullOrEmpty(sk))
            {
                return null;
            }

            return new XeapiKeyState(
                publicKey!,
                value.GetString("version", string.Empty) ?? string.Empty,
                sk!,
                value.GetLong("nextUpdateTime"));
        }

        /// <summary>把公钥状态写进设置存储（<see cref="ParseKeyState"/> 的对称写入端）。</summary>
        public void Save(XeapiKeyState state)
        {
            if (state == null)
            {
                throw new ArgumentNullException(nameof(state));
            }

            _settings.SetString(PublicKeyKey, state.PublicKey);
            _settings.SetString(PublicKeyVersionKey, state.Version);
            _settings.SetString(PublicKeySkKey, state.Sk);
            _settings.SetString(NextUpdateTimeKey, state.NextUpdateTime.ToString(CultureInfo.InvariantCulture));

            _keyState = state;
            _nextUpdateTime = state.NextUpdateTime;
        }

        private XeapiKeyState? GetFreshKeyState()
        {
            var state = _keyState;
            if (state == null)
            {
                return null;
            }

            // nextUpdateTime 缺失（0）时按「不过期」处理：业务响应解不开仍会强制刷新。
            if (_nextUpdateTime > 0 && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() >= _nextUpdateTime)
            {
                return null;
            }

            return state;
        }

        private static XeapiKeyState? LoadKeyState(ISettingsStore settings)
        {
            var publicKey = settings.GetString(PublicKeyKey);
            var sk = settings.GetString(PublicKeySkKey);
            if (string.IsNullOrEmpty(publicKey) || string.IsNullOrEmpty(sk))
            {
                return null;
            }

            var version = settings.GetString(PublicKeyVersionKey) ?? string.Empty;
            var nextUpdateTime = 0L;
            var raw = settings.GetString(NextUpdateTimeKey);
            if (!string.IsNullOrEmpty(raw))
            {
                long.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out nextUpdateTime);
            }

            return new XeapiKeyState(publicKey!, version, sk!, nextUpdateTime);
        }

        private static string LoadOrCreateDeviceId(ISettingsStore settings)
        {
            var stored = settings.GetString(DeviceIdKey);
            if (IsValidDeviceId(stored))
            {
                return stored!;
            }

            // 存量值缺失或被写坏时才生成；正常路径上这是全新设备的一次性动作。
            var deviceId = CryptoUtil.ToHex(CryptoUtil.RandomBytes(16));
            settings.SetString(DeviceIdKey, deviceId);
            return deviceId;
        }

        private static bool IsValidDeviceId(string? value)
        {
            if (value == null || value.Length != 32)
            {
                return false;
            }

            foreach (var c in value)
            {
                var isLowerHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!isLowerHex)
                {
                    return false;
                }
            }

            return true;
        }
    }
}

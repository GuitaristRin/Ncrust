using System;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Account
{
    /// <summary>
    /// 会话状态单例（界面订阅 <see cref="StateChanged"/>）。规格：<c>spec/api/entitlement.md</c> §状态模型。
    ///
    /// 存在的原因：登录失效时网易**不报错** —— <c>account/get</c> 照样 200（只是 <c>account: null</c>），
    /// 只有收藏等需登录接口回 301。客户端若只看得见「请求成功」，用户看到的就是
    /// 「每日推荐变了 / 收藏刷不出来」。对应 Android <c>account/SessionMonitor</c>。
    ///
    /// 判定时机：启动时一次、窗口重新激活且距上次判定 ≥ <see cref="RecheckInterval"/> 时一次、
    /// 任一需登录接口返回 301 时立即转 <see cref="SessionState.Expired"/>。
    ///
    /// 线程：<see cref="StateChanged"/> 在判定所在的线程上触发（可能是后台线程）——Core 不假设有 UI 线程，
    /// 由 App 负责切回 Dispatcher（见 AppServices）。
    /// </summary>
    public sealed class SessionMonitor
    {
        /// <summary>「曾登录」标记：cookie 被清掉后，界面靠它区分「登录已失效」与「从未登录」。</summary>
        public const string WasLoggedInKey = "session_was_logged_in";

        /// <summary>回前台复核的最小间隔，规格写死 30 分钟。</summary>
        public static readonly TimeSpan RecheckInterval = TimeSpan.FromMinutes(30);

        private readonly NcmHttp _http;
        private readonly ICredentialStore _credentials;
        private readonly ISettingsStore _settings;
        private readonly ISessionApi _api;
        private readonly Func<DateTimeOffset> _clock;

        // 去抖：同一时刻只允许一次 account/get 在途，并发的几个 301 不会各打一次复核网络请求。
        private readonly SemaphoreSlim _gate = new SemaphoreSlim(1, 1);

        // 会话失效 / 退出登录 / 重新登录的代号：复核期间（account/get → vip/info 两跳）若中途
        // 状态被这些事件改掉，复核结果不得把它覆盖回去（Android 同名的 expiryGeneration）。
        private int _generation;

        public SessionMonitor(
            NcmHttp http,
            ICredentialStore credentials,
            ISettingsStore settings,
            ISessionApi api,
            Func<DateTimeOffset>? clock = null)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
            _credentials = credentials ?? throw new ArgumentNullException(nameof(credentials));
            _settings = settings ?? throw new ArgumentNullException(nameof(settings));
            _api = api ?? throw new ArgumentNullException(nameof(api));
            _clock = clock ?? (() => DateTimeOffset.UtcNow);

            _http.BusinessCode += _ => MarkExpired();
        }

        /// <summary>首次判定前是 <see cref="SessionState.Unknown"/>。</summary>
        public SessionState State { get; private set; } = new SessionState.Unknown();

        /// <summary>状态变化（含 VIP 状态刷新）。可能与上一个状态同类但内容不同。</summary>
        public event Action<SessionState>? StateChanged;

        /// <summary>上一次**得出结论**的判定时间；网络失败不算得出结论，不刷新它（见 <see cref="RefreshAsync"/>）。</summary>
        public DateTimeOffset LastCheckedAt { get; private set; }

        public bool WasLoggedIn => _settings.GetBool(WasLoggedInKey, false);

        /// <summary>本地是否持有凭证：请求层有 cookie，或「曾登录」标记还在。</summary>
        public bool HasCredential => !string.IsNullOrEmpty(_http.Cookie) || WasLoggedIn;

        public bool IsValid => State is SessionState.Valid;

        /// <summary>
        /// 用 <c>account/get</c> 判定一次。<paramref name="force"/> 为 false 时受 30 分钟窗口约束。
        ///
        /// 网络失败 / 非 200 → 维持上一个已知状态，绝不因抖动判失效；且**不刷新
        /// <see cref="LastCheckedAt"/>** —— 没得出结论就不是一次「判定」，否则离线启动后
        /// 要等满 30 分钟才会再试。
        /// </summary>
        public async Task<SessionState> RefreshAsync(bool force = false, CancellationToken cancellationToken = default)
        {
            RememberLegacyCookie();

            if (!HasCredential)
            {
                SetState(new SessionState.LoggedOut());
                return State;
            }

            var now = _clock();
            if (!force && LastCheckedAt != default && now - LastCheckedAt < RecheckInterval)
            {
                return State;
            }

            if (!await _gate.WaitAsync(0).ConfigureAwait(false))
            {
                return State; // 已有一次判定在途：去抖，直接用当前状态。
            }

            var known = true;
            var generation = _generation;
            try
            {
                JsonValue? account;
                try
                {
                    account = await _api.GetAccountAsync(cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    throw; // 调用方主动取消，别吞掉。
                }
                catch
                {
                    known = false;
                    return State;
                }

                var derived = SessionState.Derive(hasCredential: true, account, null);
                if (derived is SessionState.Valid valid)
                {
                    // Valid 时顺带拉 VIP：失败（含 301）显示为未知，绝不显示「未开通」。
                    var vip = await FetchVipAsync(valid.UserId, cancellationToken).ConfigureAwait(false);

                    // 这两跳之间可能被判失效（vip/info 回 301）、被主动退出、或已重新登录：
                    // 复核结果基于旧的 cookie，不能把新的状态覆盖回去。
                    if (_generation != generation)
                    {
                        return State;
                    }

                    SetState(valid.WithVip(vip));
                }
                else if (derived is SessionState.Expired)
                {
                    MarkExpired();
                }
                else if (derived is SessionState.LoggedOut)
                {
                    SetState(derived);
                }
                else
                {
                    known = false; // Unknown：维持上一个已知状态。
                }
            }
            finally
            {
                if (known)
                {
                    LastCheckedAt = now;
                }

                _gate.Release();
            }

            return State;
        }

        /// <summary>
        /// 立即转 <see cref="SessionState.Expired"/>：清请求层与凭据库里的失效 cookie
        /// （继续携带它没有意义），保留「曾登录」标记与本地音乐库缓存。
        /// </summary>
        public SessionState MarkExpired()
        {
            if (State is SessionState.Expired)
            {
                return State;
            }

            _generation++;
            _settings.SetBool(WasLoggedInKey, true);
            _http.Cookie = null;
            _credentials.ClearCookie();
            SetState(new SessionState.Expired());
            return State;
        }

        /// <summary>
        /// 登录成功（WebView2 / 二维码）：置「曾登录」标记并立刻复核，别被 30 分钟窗口挡住。
        /// 复核期间状态先落到 <see cref="SessionState.Unknown"/>，避免上一轮的失效横幅多挂一会儿。
        /// </summary>
        public async Task<SessionState> NoteLoggedInAsync(string cookie, CancellationToken cancellationToken = default)
        {
            _generation++;
            _http.Cookie = cookie;
            _credentials.SetCookie(cookie);
            _settings.SetBool(WasLoggedInKey, true);
            LastCheckedAt = default;
            SetState(new SessionState.Unknown());
            return await RefreshAsync(force: true, cancellationToken).ConfigureAwait(false);
        }

        /// <summary>
        /// 用户主动退出登录：清掉「曾登录」标记，回到 <see cref="SessionState.LoggedOut"/>。
        ///
        /// 必须与「失效」区分：退出是用户的选择，再显示「登录已失效 · 重新登录」就成了骚扰。
        /// </summary>
        public SessionState NoteSignedOut()
        {
            _generation++;
            _settings.SetBool(WasLoggedInKey, false);
            _http.Cookie = null;
            _credentials.ClearCookie();
            SetState(new SessionState.LoggedOut());
            return State;
        }

        private async Task<VipStatus> FetchVipAsync(long userId, CancellationToken cancellationToken)
        {
            try
            {
                var response = await _api.GetVipInfoAsync(userId, cancellationToken).ConfigureAwait(false);
                return VipStatus.Derive(response, _clock().ToUnixTimeMilliseconds());
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                return new VipStatus.Unknown();
            }
        }

        /// <summary>
        /// 升级上来的老用户只有 cookie 没有标记：先补上标记，否则首次判失效后下次启动会退化成
        /// 「从未登录」，横幅文案就错了。
        /// </summary>
        private void RememberLegacyCookie()
        {
            if (!string.IsNullOrEmpty(_http.Cookie) && !WasLoggedIn)
            {
                _settings.SetBool(WasLoggedInKey, true);
            }
        }

        private void SetState(SessionState state)
        {
            if (SameState(State, state))
            {
                return;
            }

            State = state;
            StateChanged?.Invoke(state);
        }

        /// <summary>同类同内容的 Valid 不重复通知（避免每次复核都刷一遍界面）。</summary>
        private static bool SameState(SessionState left, SessionState right)
        {
            if (left is SessionState.Valid a && right is SessionState.Valid b)
            {
                return a.UserId == b.UserId
                    && string.Equals(a.Nickname, b.Nickname, StringComparison.Ordinal)
                    && SameVip(a.Vip, b.Vip);
            }

            return left.GetType() == right.GetType();
        }

        private static bool SameVip(VipStatus left, VipStatus right)
        {
            if (left is VipStatus.Active a && right is VipStatus.Active b)
            {
                return a.Kind == b.Kind && a.ExpireAt == b.ExpireAt;
            }

            if (left is VipStatus.Lapsed c && right is VipStatus.Lapsed d)
            {
                return c.Kind == d.Kind && c.ExpireAt == d.ExpireAt;
            }

            return left.GetType() == right.GetType();
        }
    }
}

using System;
using System.Collections.Generic;

namespace Ncrust.Core.Auth
{
    /// <summary>
    /// 网易云会话 Cookie 的解析 / 合并 / 序列化。对应 Android 里散落在各处的
    /// <c>cookie.split(';')</c> 处理，收拢成可测试的纯逻辑。
    ///
    /// 保留首次出现的顺序；同名以最后一次写入为准。空片段、无 <c>=</c> 的片段被忽略。
    /// </summary>
    public sealed class SessionCookie
    {
        private readonly List<KeyValuePair<string, string>> _members = new List<KeyValuePair<string, string>>();
        private readonly Dictionary<string, int> _index = new Dictionary<string, int>(StringComparer.Ordinal);

        public int Count => _members.Count;

        public IReadOnlyList<KeyValuePair<string, string>> Members => _members;

        public string? MusicU => Get("MUSIC_U");

        public string? Csrf => Get("__csrf");

        public static SessionCookie Parse(string? cookie)
        {
            var result = new SessionCookie();
            if (string.IsNullOrEmpty(cookie))
            {
                return result;
            }

            foreach (var part in cookie!.Split(';'))
            {
                var trimmed = part.Trim();
                var eq = trimmed.IndexOf('=');
                if (eq <= 0)
                {
                    continue;
                }

                result.Set(trimmed.Substring(0, eq).Trim(), trimmed.Substring(eq + 1));
            }

            return result;
        }

        public bool Contains(string name) => _index.ContainsKey(name);

        public string? Get(string name) => _index.TryGetValue(name, out var i) ? _members[i].Value : null;

        public bool TryGet(string name, out string value)
        {
            if (_index.TryGetValue(name, out var i))
            {
                value = _members[i].Value;
                return true;
            }

            value = string.Empty;
            return false;
        }

        /// <summary>写入 / 覆盖一个字段，返回自身以支持链式调用。</summary>
        public SessionCookie Set(string name, string value)
        {
            if (_index.TryGetValue(name, out var i))
            {
                _members[i] = new KeyValuePair<string, string>(name, value);
            }
            else
            {
                _index[name] = _members.Count;
                _members.Add(new KeyValuePair<string, string>(name, value));
            }

            return this;
        }

        /// <summary>把 <paramref name="other"/> 里的字段合并进来（覆盖同名）。</summary>
        public SessionCookie Merge(SessionCookie? other)
        {
            if (other != null)
            {
                foreach (var member in other._members)
                {
                    Set(member.Key, member.Value);
                }
            }

            return this;
        }

        public override string ToString()
        {
            var sb = new System.Text.StringBuilder();
            foreach (var member in _members)
            {
                if (sb.Length > 0)
                {
                    sb.Append("; ");
                }

                sb.Append(member.Key).Append('=').Append(member.Value);
            }

            return sb.ToString();
        }
    }
}

using System.Collections.Generic;
using System.Text;

namespace Ncrust.Core.Net
{
    /// <summary>
    /// 最小 JSON 写入器：只处理「有序的字符串键值对 → JSON 对象」这一种形状。
    /// eapi / weapi 的请求体都是扁平的字符串映射，用它拼装可以完全不引入 JSON 库，
    /// 也不受各端反射裁剪策略影响（netstandard2.0 + .NET Native）。读取响应仍由
    /// 上层用项目统一的 JSON 方案处理。
    /// </summary>
    internal static class JsonText
    {
        public static string Object(IEnumerable<KeyValuePair<string, string>> pairs)
        {
            var sb = new StringBuilder();
            sb.Append('{');
            var first = true;
            foreach (var pair in pairs)
            {
                if (!first)
                {
                    sb.Append(',');
                }

                first = false;
                AppendString(sb, pair.Key);
                sb.Append(':');
                AppendString(sb, pair.Value);
            }

            sb.Append('}');
            return sb.ToString();
        }

        public static string Escape(string value)
        {
            var sb = new StringBuilder(value.Length + 2);
            AppendString(sb, value);
            return sb.ToString();
        }

        private static void AppendString(StringBuilder sb, string value)
        {
            sb.Append('"');
            foreach (var c in value)
            {
                switch (c)
                {
                    case '"':
                        sb.Append("\\\"");
                        break;
                    case '\\':
                        sb.Append("\\\\");
                        break;
                    case '\b':
                        sb.Append("\\b");
                        break;
                    case '\f':
                        sb.Append("\\f");
                        break;
                    case '\n':
                        sb.Append("\\n");
                        break;
                    case '\r':
                        sb.Append("\\r");
                        break;
                    case '\t':
                        sb.Append("\\t");
                        break;
                    default:
                        if (c < ' ')
                        {
                            sb.Append("\\u").Append(((int)c).ToString("x4"));
                        }
                        else
                        {
                            sb.Append(c);
                        }

                        break;
                }
            }

            sb.Append('"');
        }
    }
}

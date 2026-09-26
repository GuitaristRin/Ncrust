using System;
using System.Collections.Generic;
using System.Globalization;

namespace Ncrust.Core.Json
{
    /// <summary>JSON 值的类型。</summary>
    public enum JsonKind
    {
        Null,
        Bool,
        Number,
        String,
        Array,
        Object,
    }

    /// <summary>解析失败时抛出，带出错位置。</summary>
    public sealed class JsonParseException : Exception
    {
        public JsonParseException(string message, int position)
            : base($"{message}（位置 {position}）")
        {
            Position = position;
        }

        public int Position { get; }
    }

    /// <summary>
    /// 只读 JSON DOM。**自研、零反射、零外部依赖**：netstandard2.0 即可用，.NET Native
    /// 裁剪不会误删，Android 端也能照搬。只负责读取；请求体的写由
    /// <see cref="Ncrust.Core.Net.JsonText"/> 负责。
    ///
    /// 选择它而非 System.Text.Json / Newtonsoft，是因为 windows/AGENTS.md 把「.NET Native
    /// 对反射敏感」列为硬约束；这里没有反射、没有源生成器，M0 #5 的验证只需确认它能在
    /// Release + .NET Native 下正常解析即可。
    /// </summary>
    public sealed class JsonValue
    {
        private readonly JsonKind _kind;
        private readonly string? _text;
        private readonly bool _bool;
        private readonly List<JsonValue>? _items;
        private readonly List<KeyValuePair<string, JsonValue>>? _members;
        private readonly Dictionary<string, JsonValue>? _index;

        private JsonValue(JsonKind kind)
        {
            _kind = kind;
        }

        private JsonValue(string text)
        {
            _kind = JsonKind.String;
            _text = text;
        }

        private JsonValue(JsonKind kind, string text)
        {
            _kind = kind;
            _text = text;
        }

        private JsonValue(bool value)
        {
            _kind = JsonKind.Bool;
            _bool = value;
        }

        private JsonValue(List<JsonValue> items)
        {
            _kind = JsonKind.Array;
            _items = items;
        }

        private JsonValue(List<KeyValuePair<string, JsonValue>> members)
        {
            _kind = JsonKind.Object;
            _members = members;
            _index = new Dictionary<string, JsonValue>(members.Count, StringComparer.Ordinal);
            foreach (var member in members)
            {
                _index[member.Key] = member.Value;
            }
        }

        public static JsonValue Null { get; } = new JsonValue(JsonKind.Null);

        public JsonKind Kind => _kind;

        public bool IsNull => _kind == JsonKind.Null;
        public bool IsObject => _kind == JsonKind.Object;
        public bool IsArray => _kind == JsonKind.Array;
        public bool IsString => _kind == JsonKind.String;
        public bool IsNumber => _kind == JsonKind.Number;
        public bool IsBool => _kind == JsonKind.Bool;

        /// <summary>数组长度 / 对象成员数；其他类型为 0。</summary>
        public int Count => _kind == JsonKind.Array ? _items!.Count : _kind == JsonKind.Object ? _members!.Count : 0;

        /// <summary>按插入顺序的数组元素 / 对象成员。</summary>
        public IReadOnlyList<JsonValue> Items =>
            _items ?? throw new InvalidOperationException($"不是数组（实际是 {_kind}）");

        public IReadOnlyList<KeyValuePair<string, JsonValue>> Members =>
            _members ?? throw new InvalidOperationException($"不是对象（实际是 {_kind}）");

        public string? StringValue => _kind == JsonKind.String ? _text : null;

        public bool BoolValue => _kind == JsonKind.Bool && _bool;

        public long LongValue
        {
            get
            {
                if (_kind != JsonKind.Number)
                {
                    return 0;
                }

                return long.TryParse(_text!, NumberStyles.Integer, CultureInfo.InvariantCulture, out var value)
                    ? value
                    : (long)DoubleValue;
            }
        }

        public double DoubleValue => _kind == JsonKind.Number
            ? double.Parse(_text!, NumberStyles.Float, CultureInfo.InvariantCulture)
            : 0;

        public JsonValue? this[string key] =>
            _kind == JsonKind.Object && key != null && _index!.TryGetValue(key, out var value) ? value : null;

        public JsonValue? this[int index] =>
            _kind == JsonKind.Array && index >= 0 && index < _items!.Count ? _items[index] : null;

        public bool Has(string key) => this[key] != null;

        public string? GetString(string key, string? fallback = null) => this[key]?.StringValue ?? fallback;

        public long GetLong(string key, long fallback = 0)
        {
            var value = this[key];
            return value != null && value.IsNumber ? value.LongValue : fallback;
        }

        public int GetInt(string key, int fallback = 0)
        {
            var value = this[key];
            return value != null && value.IsNumber ? (int)value.LongValue : fallback;
        }

        public double GetDouble(string key, double fallback = 0)
        {
            var value = this[key];
            return value != null && value.IsNumber ? value.DoubleValue : fallback;
        }

        public bool GetBool(string key, bool fallback = false)
        {
            var value = this[key];
            return value != null && value.IsBool ? value.BoolValue : fallback;
        }

        public IReadOnlyList<JsonValue>? GetArray(string key)
        {
            var value = this[key];
            return value != null && value.IsArray ? value.Items : null;
        }

        public JsonValue? GetObject(string key)
        {
            var value = this[key];
            return value != null && value.IsObject ? value : null;
        }

        public static JsonValue Parse(string text)
        {
            if (text == null)
            {
                throw new ArgumentNullException(nameof(text));
            }

            var parser = new Parser(text);
            var value = parser.ParseValue();
            parser.SkipWhitespace();
            if (!parser.AtEnd)
            {
                throw new JsonParseException("JSON 结束后还有多余内容", parser.Position);
            }

            return value;
        }

        private sealed class Parser
        {
            private readonly string _text;
            private int _pos;

            public Parser(string text)
            {
                _text = text;
                if (_text.Length > 0 && _text[0] == '\uFEFF')
                {
                    _pos = 1; // 跳过 UTF-8 BOM 解码后的残留
                }
            }

            public int Position => _pos;
            public bool AtEnd => _pos >= _text.Length;

            public void SkipWhitespace()
            {
                while (_pos < _text.Length)
                {
                    var c = _text[_pos];
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
                    {
                        _pos++;
                    }
                    else
                    {
                        break;
                    }
                }
            }

            public JsonValue ParseValue()
            {
                SkipWhitespace();
                if (AtEnd)
                {
                    throw new JsonParseException("期望一个值，但已到结尾", _pos);
                }

                switch (_text[_pos])
                {
                    case '{':
                        return ParseObject();
                    case '[':
                        return ParseArray();
                    case '"':
                        return new JsonValue(ParseString());
                    case 't':
                        Expect("true");
                        return new JsonValue(true);
                    case 'f':
                        Expect("false");
                        return new JsonValue(false);
                    case 'n':
                        Expect("null");
                        return Null;
                    default:
                        return new JsonValue(JsonKind.Number, ParseNumber());
                }
            }

            private JsonValue ParseObject()
            {
                _pos++; // {
                var members = new List<KeyValuePair<string, JsonValue>>();
                SkipWhitespace();
                if (!AtEnd && _text[_pos] == '}')
                {
                    _pos++;
                    return new JsonValue(members);
                }

                while (true)
                {
                    SkipWhitespace();
                    if (AtEnd || _text[_pos] != '"')
                    {
                        throw new JsonParseException("对象的键必须是字符串", _pos);
                    }

                    var key = ParseString();
                    SkipWhitespace();
                    if (AtEnd || _text[_pos] != ':')
                    {
                        throw new JsonParseException("键后缺少 ':'", _pos);
                    }

                    _pos++;
                    var value = ParseValue();
                    // 重复键以最后一个为准，但保留首次出现的位置顺序。
                    var replaced = false;
                    for (var i = 0; i < members.Count; i++)
                    {
                        if (string.Equals(members[i].Key, key, StringComparison.Ordinal))
                        {
                            members[i] = new KeyValuePair<string, JsonValue>(key, value);
                            replaced = true;
                            break;
                        }
                    }

                    if (!replaced)
                    {
                        members.Add(new KeyValuePair<string, JsonValue>(key, value));
                    }

                    SkipWhitespace();
                    if (AtEnd)
                    {
                        throw new JsonParseException("对象未闭合", _pos);
                    }

                    if (_text[_pos] == ',')
                    {
                        _pos++;
                        continue;
                    }

                    if (_text[_pos] == '}')
                    {
                        _pos++;
                        return new JsonValue(members);
                    }

                    throw new JsonParseException("对象里期望 ',' 或 '}'", _pos);
                }
            }

            private JsonValue ParseArray()
            {
                _pos++; // [
                var items = new List<JsonValue>();
                SkipWhitespace();
                if (!AtEnd && _text[_pos] == ']')
                {
                    _pos++;
                    return new JsonValue(items);
                }

                while (true)
                {
                    items.Add(ParseValue());
                    SkipWhitespace();
                    if (AtEnd)
                    {
                        throw new JsonParseException("数组未闭合", _pos);
                    }

                    if (_text[_pos] == ',')
                    {
                        _pos++;
                        continue;
                    }

                    if (_text[_pos] == ']')
                    {
                        _pos++;
                        return new JsonValue(items);
                    }

                    throw new JsonParseException("数组里期望 ',' 或 ']'", _pos);
                }
            }

            private string ParseString()
            {
                _pos++; // 开引号
                var sb = new System.Text.StringBuilder();
                while (true)
                {
                    if (AtEnd)
                    {
                        throw new JsonParseException("字符串未闭合", _pos);
                    }

                    var c = _text[_pos++];
                    if (c == '"')
                    {
                        return sb.ToString();
                    }

                    if (c != '\\')
                    {
                        if (c < ' ')
                        {
                            throw new JsonParseException("字符串里不允许的控制字符", _pos - 1);
                        }

                        sb.Append(c);
                        continue;
                    }

                    if (AtEnd)
                    {
                        throw new JsonParseException("转义序列不完整", _pos);
                    }

                    var esc = _text[_pos++];
                    switch (esc)
                    {
                        case '"':
                            sb.Append('"');
                            break;
                        case '\\':
                            sb.Append('\\');
                            break;
                        case '/':
                            sb.Append('/');
                            break;
                        case 'b':
                            sb.Append('\b');
                            break;
                        case 'f':
                            sb.Append('\f');
                            break;
                        case 'n':
                            sb.Append('\n');
                            break;
                        case 'r':
                            sb.Append('\r');
                            break;
                        case 't':
                            sb.Append('\t');
                            break;
                        case 'u':
                            sb.Append(ParseUnicodeEscape());
                            break;
                        default:
                            throw new JsonParseException($"未知的转义 \\{esc}", _pos - 1);
                    }
                }
            }

            private char ParseUnicodeEscape()
            {
                if (_pos + 4 > _text.Length)
                {
                    throw new JsonParseException("\\u 转义不完整", _pos);
                }

                var value = 0;
                for (var i = 0; i < 4; i++)
                {
                    var digit = HexValue(_text[_pos + i]);
                    if (digit < 0)
                    {
                        throw new JsonParseException("\\u 转义里出现非十六进制字符", _pos + i);
                    }

                    value = (value << 4) | digit;
                }

                _pos += 4;
                return (char)value;
            }

            private static int HexValue(char c)
            {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                if (c >= 'A' && c <= 'F') return c - 'A' + 10;
                return -1;
            }

            private string ParseNumber()
            {
                var start = _pos;
                if (!AtEnd && _text[_pos] == '-')
                {
                    _pos++;
                }

                var digits = 0;
                while (!AtEnd && _text[_pos] >= '0' && _text[_pos] <= '9')
                {
                    _pos++;
                    digits++;
                }

                if (digits == 0)
                {
                    throw new JsonParseException("数字格式非法", start);
                }

                if (!AtEnd && _text[_pos] == '.')
                {
                    _pos++;
                    var frac = 0;
                    while (!AtEnd && _text[_pos] >= '0' && _text[_pos] <= '9')
                    {
                        _pos++;
                        frac++;
                    }

                    if (frac == 0)
                    {
                        throw new JsonParseException("小数点后缺少数位", _pos);
                    }
                }

                if (!AtEnd && (_text[_pos] == 'e' || _text[_pos] == 'E'))
                {
                    _pos++;
                    if (!AtEnd && (_text[_pos] == '+' || _text[_pos] == '-'))
                    {
                        _pos++;
                    }

                    var exp = 0;
                    while (!AtEnd && _text[_pos] >= '0' && _text[_pos] <= '9')
                    {
                        _pos++;
                        exp++;
                    }

                    if (exp == 0)
                    {
                        throw new JsonParseException("指数部分缺少数位", _pos);
                    }
                }

                return _text.Substring(start, _pos - start);
            }

            private void Expect(string literal)
            {
                if (_pos + literal.Length > _text.Length ||
                    string.CompareOrdinal(_text, _pos, literal, 0, literal.Length) != 0)
                {
                    throw new JsonParseException($"期望 '{literal}'", _pos);
                }

                _pos += literal.Length;
            }
        }
    }
}

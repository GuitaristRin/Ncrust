#pragma once

#include <windows.h>

#include <cstdint>
#include <string>
#include <string_view>

namespace ritsudou
{
    inline std::wstring utf8ToWide(std::string_view s)
    {
        if (s.empty()) return L"";
        int n = ::MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
        std::wstring out(static_cast<size_t>(n), L'\0');
        ::MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &out[0], n);
        return out;
    }

    inline std::string wideToUtf8(std::wstring_view s)
    {
        if (s.empty()) return "";
        int n = ::WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0, nullptr, nullptr);
        std::string out(static_cast<size_t>(n), '\0');
        ::WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &out[0], n, nullptr, nullptr);
        return out;
    }

    inline std::wstring toWideString(int64_t v)
    {
        return std::to_wstring(v);
    }

    // 与 org.json 一致的转义：只处理双引号 / 反斜杠 / 控制字符。
    inline std::string jsonEscape(std::wstring_view s)
    {
        std::string out;
        out.reserve(s.size());
        for (wchar_t c : s)
        {
            switch (c)
            {
            case L'"': out += "\\\""; break;
            case L'\\': out += "\\\\"; break;
            case L'\n': out += "\\n"; break;
            case L'\r': out += "\\r"; break;
            case L'\t': out += "\\t"; break;
            case L'\b': out += "\\b"; break;
            case L'\f': out += "\\f"; break;
            default:
                if (c < 0x20)
                {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                    out += buf;
                }
                else
                {
                    out += wideToUtf8(std::wstring_view(&c, 1));
                }
            }
        }
        return out;
    }

    // 表单 URL 编码（UTF-8 字节级），保留非保留字符。
    inline std::wstring urlEncode(std::wstring_view s)
    {
        std::string utf8 = wideToUtf8(s);
        static const char hex[] = "0123456789ABCDEF";
        std::wstring out;
        out.reserve(utf8.size() * 3);
        auto isUnreserved = [](unsigned char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
        };
        for (unsigned char c : utf8)
        {
            if (isUnreserved(c))
            {
                out.push_back(static_cast<wchar_t>(c));
            }
            else
            {
                out.push_back(L'%');
                out.push_back(static_cast<wchar_t>(hex[c >> 4]));
                out.push_back(static_cast<wchar_t>(hex[c & 0x0F]));
            }
        }
        return out;
    }
}

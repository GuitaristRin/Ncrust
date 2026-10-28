#include "pch.h"
#include "LrcParser.h"

#include <algorithm>

namespace ritsudou
{
    namespace
    {
        void trim(std::wstring& s)
        {
            size_t b = 0;
            while (b < s.size() && (s[b] == L' ' || s[b] == L'\t' || s[b] == L'\r')) ++b;
            size_t e = s.size();
            while (e > b && (s[e - 1] == L' ' || s[e - 1] == L'\t' || s[e - 1] == L'\r')) --e;
            s = s.substr(b, e - b);
        }

        int parseNum(std::wstring_view s, int def = 0)
        {
            if (s.empty()) return def;
            int v = 0;
            for (wchar_t c : s)
            {
                if (c < L'0' || c > L'9') return def;
                v = v * 10 + (c - L'0');
            }
            return v;
        }
    }

    std::vector<LyricLine> LrcParser::parse(std::wstring_view text)
    {
        std::vector<LyricLine> out;

        size_t pos = 0;
        while (pos < text.size())
        {
            size_t nl = text.find(L'\n', pos);
            std::wstring line(text.substr(pos, (nl == std::wstring::npos) ? text.size() - pos : nl - pos));
            pos = (nl == std::wstring::npos) ? text.size() : nl + 1;

            trim(line);
            if (line.size() < 8 || line[0] != L'[') continue;

            size_t rb = line.find(L']');
            if (rb == std::wstring::npos) continue;

            std::wstring ts = line.substr(1, rb - 1);
            size_t colon = ts.find(L':');
            size_t dot = ts.find(L'.');
            // 与 Android 正则 [MM:SS.mm] / [MM:SS.mmm] 严格对齐：
            // mm/ss 恰好 2 位，毫秒 2–3 位，否则跳过该行。
            if (colon != 2 || dot == std::wstring::npos || dot - colon != 3) continue;
            std::wstring fracStr(ts.substr(dot + 1));
            if (fracStr.size() != 2 && fracStr.size() != 3) continue;

            const int mm = parseNum(std::wstring_view(ts).substr(0, 2));
            const int ss = parseNum(std::wstring_view(ts).substr(3, 2));
            int ms = parseNum(fracStr);
            if (fracStr.size() == 2) ms *= 10;

            std::wstring content = line.substr(rb + 1);
            trim(content);
            if (!content.empty())
            {
                out.push_back(LyricLine{ mm * 60000LL + ss * 1000LL + ms, std::move(content) });
            }
        }

        std::sort(out.begin(), out.end(), [](LyricLine const& a, LyricLine const& b) {
            return a.timeMs < b.timeMs;
        });
        return out;
    }
}

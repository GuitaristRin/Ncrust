#pragma once

#include "../model/Models.h"

#include <string>
#include <vector>

namespace ritsudou
{
    // LRC 解析：[MM:SS.mm] 或 [MM:SS.mmm]，毫秒两位时 *10（与 Android 版一致）。
    class LrcParser
    {
    public:
        static std::vector<LyricLine> parse(std::wstring_view text);
    };
}

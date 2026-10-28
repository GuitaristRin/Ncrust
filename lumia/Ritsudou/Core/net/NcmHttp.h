#pragma once

#include <winrt/Windows.Web.Http.h>

#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace ritsudou
{
    // 网络层：基于 Windows.Web.Http，手动注入 Cookie / UA / Referer。
    // 全部为同步阻塞调用 —— 设计上只允许在后台线程使用（UI 层用
    // resume_background 切线程后调用），避免 UI 线程死锁。
    class NcmHttp
    {
    public:
        static void setCookie(std::wstring_view cookie);
        static std::wstring cookie();

        static std::wstring get(std::wstring_view path, bool useInterface = false);
        static std::wstring postForm(std::wstring_view path,
            const std::vector<std::pair<std::wstring, std::wstring>>& fields,
            bool useInterface = false);
        static std::wstring eapiPost(std::wstring_view path,
            const std::vector<std::pair<std::wstring, std::wstring>>& payload,
            bool useInterface = false);

    private:
        static std::wstring request(winrt::Windows::Web::Http::HttpMethod const& method,
            std::wstring_view path, bool useInterface,
            const std::wstring& body, const std::wstring& contentType);
    };
}

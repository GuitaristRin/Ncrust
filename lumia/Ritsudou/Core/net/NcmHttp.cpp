#include "pch.h"
#include "NcmHttp.h"

#include "../crypto/EapiCrypto.h"
#include "../util/Text.h"

namespace ritsudou
{
    namespace
    {
        constexpr std::wstring_view kBaseUrl = L"https://music.163.com";
        constexpr std::wstring_view kInterfaceUrl = L"https://interface3.music.163.com";
        constexpr std::wstring_view kUserAgent = L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
        constexpr std::wstring_view kReferer = L"https://music.163.com/";

        winrt::Windows::Web::Http::HttpClient& sharedClient()
        {
            // 函数内静态：首次使用即初始化；HttpClient 是 agile 对象，跨线程复用安全。
            static winrt::Windows::Web::Http::HttpClient client;
            return client;
        }
    }

    std::wstring cookieValue;

    void NcmHttp::setCookie(std::wstring_view cookie)
    {
        cookieValue = std::wstring(cookie);
    }

    std::wstring NcmHttp::cookie()
    {
        return cookieValue;
    }

    std::wstring NcmHttp::request(winrt::Windows::Web::Http::HttpMethod const& method,
        std::wstring_view path, bool useInterface,
        const std::wstring& body, const std::wstring& contentType)
    {
        std::wstring host(useInterface ? kInterfaceUrl : kBaseUrl);
        winrt::Windows::Foundation::Uri uri{ winrt::hstring{ host + std::wstring(path) } };

        winrt::Windows::Web::Http::HttpRequestMessage req{ method, uri };
        auto headers = req.Headers();
        headers.UserAgent().ParseAdd(winrt::hstring{ kUserAgent });
        headers.TryAppendWithoutValidation(L"Referer", winrt::hstring{ kReferer });
        if (!cookieValue.empty())
        {
            headers.TryAppendWithoutValidation(L"Cookie", winrt::hstring{ cookieValue });
        }

        if (!body.empty())
        {
            req.Content(winrt::Windows::Web::Http::HttpStringContent{
                winrt::hstring{ body },
                winrt::Windows::Storage::Streams::UnicodeEncoding::Utf8,
                winrt::hstring{ contentType } });
        }

        auto response = sharedClient().SendRequestAsync(req).get();
        if (response.StatusCode() != winrt::Windows::Web::Http::HttpStatusCode::Ok)
        {
            throw std::runtime_error("HTTP " + std::to_string(static_cast<int>(response.StatusCode())));
        }
        return std::wstring(response.Content().ReadAsStringAsync().get());
    }

    std::wstring NcmHttp::get(std::wstring_view path, bool useInterface)
    {
        return request(winrt::Windows::Web::Http::HttpMethod::Get(), path, useInterface, L"", L"");
    }

    std::wstring NcmHttp::postForm(std::wstring_view path,
        const std::vector<std::pair<std::wstring, std::wstring>>& fields,
        bool useInterface)
    {
        std::wstring form;
        bool first = true;
        for (auto const& [k, v] : fields)
        {
            if (!first) form += L"&";
            first = false;
            form += urlEncode(k) + L"=" + urlEncode(v);
        }
        return request(winrt::Windows::Web::Http::HttpMethod::Post(), path, useInterface,
            form, L"application/x-www-form-urlencoded");
    }

    std::wstring NcmHttp::eapiPost(std::wstring_view path,
        const std::vector<std::pair<std::wstring, std::wstring>>& payload,
        bool useInterface)
    {
        std::wstring host(useInterface ? kInterfaceUrl : kBaseUrl);
        std::string payloadJson = "{";
        bool first = true;
        for (auto const& [k, v] : payload)
        {
            if (!first) payloadJson += ",";
            first = false;
            payloadJson += "\"" + wideToUtf8(k) + "\":\"" + jsonEscape(v) + "\"";
        }
        payloadJson += "}";

        const std::string fullUrl = wideToUtf8(host) + wideToUtf8(path);
        const std::string params = EapiCrypto::encryptParams(fullUrl, payloadJson);

        std::wstring form = L"params=" + urlEncode(utf8ToWide(params));
        return request(winrt::Windows::Web::Http::HttpMethod::Post(), path, useInterface,
            form, L"application/x-www-form-urlencoded");
    }
}

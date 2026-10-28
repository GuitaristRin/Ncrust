#include "pch.h"
#include "LibraryManager.h"

#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Storage.h>

#include <algorithm>

namespace ritsudou
{
    namespace
    {
        using winrt::Windows::Data::Json::JsonArray;
        using winrt::Windows::Data::Json::JsonObject;
        using winrt::Windows::Data::Json::JsonValue;

        constexpr wchar_t kKey[] = L"Ritsudou_Library_v1";

        // 收藏持久化格式：JSON 数组，每项 {id,name,dt,ar:[{id,name}],al:{id,name,picUrl}}。
        // 与 NcmApi 解析方向互为逆操作，字段名与歌曲详情返回保持一致。
        JsonObject songToJson(const Song& s)
        {
            JsonObject o{};
            o.SetNamedValue(L"id", JsonValue::CreateNumberValue(static_cast<double>(s.id)));
            o.SetNamedValue(L"name", JsonValue::CreateStringValue(winrt::hstring{ s.name }));
            o.SetNamedValue(L"dt", JsonValue::CreateNumberValue(static_cast<double>(s.durationMs)));

            JsonArray ar{};
            for (auto const& a : s.artists)
            {
                JsonObject ao{};
                ao.SetNamedValue(L"id", JsonValue::CreateNumberValue(static_cast<double>(a.id)));
                ao.SetNamedValue(L"name", JsonValue::CreateStringValue(winrt::hstring{ a.name }));
                ar.Append(ao);
            }
            o.SetNamedValue(L"ar", ar);

            JsonObject al{};
            al.SetNamedValue(L"id", JsonValue::CreateNumberValue(static_cast<double>(s.album.id)));
            al.SetNamedValue(L"name", JsonValue::CreateStringValue(winrt::hstring{ s.album.name }));
            al.SetNamedValue(L"picUrl", JsonValue::CreateStringValue(winrt::hstring{ s.album.picUrl }));
            o.SetNamedValue(L"al", al);

            return o;
        }

        Song songFromJson(JsonObject const& o)
        {
            Song s;
            try { s.id = static_cast<int64_t>(o.GetNamedNumber(L"id")); } catch (...) {}
            try { s.name = std::wstring(o.GetNamedString(L"name")); } catch (...) {}
            try { s.durationMs = static_cast<int64_t>(o.GetNamedNumber(L"dt")); } catch (...) {}

            try
            {
                auto ar = o.GetNamedArray(L"ar");
                for (uint32_t i = 0; i < ar.Size(); ++i)
                {
                    try
                    {
                        auto ao = ar.GetAt(i).GetObject();
                        Artist a;
                        a.id = static_cast<int64_t>(ao.GetNamedNumber(L"id"));
                        a.name = std::wstring(ao.GetNamedString(L"name"));
                        s.artists.push_back(a);
                    }
                    catch (...) {}
                }
            }
            catch (...) {}

            try
            {
                auto al = o.GetNamedObject(L"al");
                s.album.id = static_cast<int64_t>(al.GetNamedNumber(L"id"));
                s.album.name = std::wstring(al.GetNamedString(L"name"));
                s.album.picUrl = std::wstring(al.GetNamedString(L"picUrl"));
            }
            catch (...) {}

            return s;
        }
    }

    LibraryManager& LibraryManager::instance()
    {
        static LibraryManager manager;
        return manager;
    }

    void LibraryManager::ensureLoaded()
    {
        if (loaded_) return;
        loaded_ = true;

        try
        {
            auto settings = winrt::Windows::Storage::ApplicationData::Current().LocalSettings();
            if (!settings.Values().HasKey(winrt::hstring{ kKey })) return;

            auto json = winrt::unbox_value<winrt::hstring>(settings.Values().Lookup(winrt::hstring{ kKey }));
            auto array = JsonArray::Parse(json);

            songs_.clear();
            for (uint32_t i = 0; i < array.Size(); ++i)
            {
                try
                {
                    songs_.push_back(songFromJson(array.GetAt(i).GetObject()));
                }
                catch (...) {}
            }
        }
        catch (...)
        {
            // 解析失败视为空收藏，不阻塞启动。
        }
    }

    bool LibraryManager::isSaved(int64_t songId) const
    {
        return std::any_of(songs_.begin(), songs_.end(),
            [songId](Song const& s) { return s.id == songId; });
    }

    bool LibraryManager::toggle(const Song& song)
    {
        ensureLoaded();
        auto it = std::find_if(songs_.begin(), songs_.end(),
            [&](Song const& s) { return s.id == song.id; });
        if (it != songs_.end())
        {
            songs_.erase(it);
            save();
            return false;
        }
        songs_.push_back(song);
        save();
        return true;
    }

    void LibraryManager::removeSong(int64_t songId)
    {
        ensureLoaded();
        auto it = std::remove_if(songs_.begin(), songs_.end(),
            [songId](Song const& s) { return s.id == songId; });
        if (it != songs_.end())
        {
            songs_.erase(it, songs_.end());
            save();
        }
    }

    void LibraryManager::save()
    {
        try
        {
            JsonArray array{};
            for (auto const& s : songs_)
            {
                array.Append(songToJson(s));
            }
            auto json = array.Stringify();
            auto settings = winrt::Windows::Storage::ApplicationData::Current().LocalSettings();
            settings.Values().Insert(winrt::hstring{ kKey },
                winrt::box_value(winrt::hstring{ json }));
        }
        catch (...)
        {
        }
    }
}

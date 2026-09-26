using System;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Net;
using Xunit;

namespace Ncrust.Core.Tests;

public class DiscoveryApiTests
{
    [Fact]
    public async Task DailyRecommend_ParsesNewAndOldSongShapes()
    {
        const string body = """
        {"code":200,"recommend":[
          {"id":1,"name":"新格式","ar":[{"id":10,"name":"A"}],"al":{"id":20,"name":"专辑","picUrl":"p"},"dt":1234},
          {"id":2,"name":"老格式","artists":[{"id":0,"name":"B"}],"album":{"id":0,"name":"专辑2","picUrl":"q"},"duration":5678}
        ]}
        """;
        var api = Api(_ => Json(body));

        var songs = await api.GetDailyRecommendSongsAsync();

        Assert.Equal(2, songs.Count);
        Assert.Equal("新格式", songs[0].Name);
        Assert.Equal(10, songs[0].Artists[0].Id);
        Assert.Equal("专辑", songs[0].Album!.Name);
        Assert.Equal(1234, songs[0].Duration);
        // id / albumId 为 0 时视为未知（null），与 Android 的 takeIf { it != 0L } 一致。
        Assert.Null(songs[1].Artists[0].Id);
        Assert.Null(songs[1].Album!.Id);
        Assert.Equal(5678, songs[1].Duration);
    }

    [Fact]
    public async Task DailyRecommend_FallsBackToDataArray()
    {
        var api = Api(_ => Json("{\"code\":200,\"data\":[{\"id\":9,\"name\":\"x\"}]}"));

        var songs = await api.GetDailyRecommendSongsAsync();

        Assert.Single(songs);
        Assert.Equal(9, songs[0].Id);
    }

    [Fact]
    public async Task RecommendPlaylists_CapsAtTen_AndFixesPrivateRadarCount()
    {
        var items = new StringBuilder();
        for (var i = 0; i < 12; i++)
        {
            if (i > 0) items.Append(',');
            var name = i == 0 ? "私人雷达" : "歌单" + i;
            items.Append($"{{\"id\":{i},\"name\":\"{name}\",\"picUrl\":\"p{i}\",\"playCount\":{i * 10},\"trackCount\":{i}}}");
        }

        var api = Api(_ => Json($"{{\"code\":200,\"recommend\":[{items}]}}"));

        var playlists = await api.GetRecommendPlaylistsAsync();

        Assert.Equal(10, playlists.Count);
        Assert.Equal(35, playlists[0].TrackCount);
        Assert.Equal("歌单1", playlists[1].Name);
    }

    [Fact]
    public async Task TopSongs_UsesRestGetWithQuery()
    {
        var handler = new FakeHandler(_ => Json("{\"data\":[{\"id\":3,\"name\":\"s\"}]}"));
        var api = new DiscoveryApi(new NcmHttp(handler));

        var songs = await api.GetTopSongsAsync(limit: 5, offset: 10);

        Assert.Single(songs);
        Assert.Equal("https://music.163.com/api/v1/discovery/new/songs?limit=5&offset=10", handler.LastUri);
    }

    [Fact]
    public async Task PersonalFm_UnwrapsNestedSongAndFlatSong()
    {
        const string body = """
        {"code":200,"data":[
          {"song":{"id":1,"name":"nested","ar":[{"name":"A"}]}},
          {"id":2,"name":"flat","artists":[{"name":"B"}]}
        ]}
        """;
        var api = Api(_ => Json(body));

        var songs = await api.GetPersonalFmAsync();

        Assert.Equal(2, songs.Count);
        Assert.Equal("nested", songs[0].Name);
        Assert.Equal("flat", songs[1].Name);
    }

    [Fact]
    public async Task MissingStructure_ReturnsEmpty()
    {
        var api = Api(_ => Json("{\"code\":200}"));

        Assert.Empty(await api.GetDailyRecommendSongsAsync());
        Assert.Empty(await api.GetRecommendPlaylistsAsync());
        Assert.Empty(await api.GetPersonalFmAsync());
    }

    private static DiscoveryApi Api(Func<HttpRequestMessage, HttpResponseMessage> respond) =>
        new DiscoveryApi(new NcmHttp(new FakeHandler(respond)));

    private static HttpResponseMessage Json(string body) => new HttpResponseMessage(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _respond;

        public FakeHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) => _respond = respond;

        public string? LastUri { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            LastUri = request.RequestUri?.ToString();
            return Task.FromResult(_respond(request));
        }
    }
}

using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Net;
using Xunit;

namespace Ncrust.Core.Tests;

public class ApiEndpointTests
{
    [Fact]
    public async Task SearchSongs_ParsesResultSongs()
    {
        var api = new SearchApi(Client("/api/cloudsearch/pc",
            "{\"result\":{\"songs\":[{\"id\":1,\"name\":\"n\",\"ar\":[{\"id\":2,\"name\":\"a\"}],\"al\":{\"id\":3,\"name\":\"al\",\"picUrl\":\"p\"},\"dt\":100}]}}"));

        var songs = await api.SearchSongsAsync("hi");

        Assert.Single(songs);
        Assert.Equal("n", songs[0].Name);
        Assert.Equal(2, songs[0].Artists[0].Id);
    }

    [Fact]
    public async Task SearchAlbums_ParsesArtist()
    {
        var api = new SearchApi(Client("/api/cloudsearch/pc",
            "{\"result\":{\"albums\":[{\"id\":1,\"name\":\"a\",\"picUrl\":\"p\",\"artist\":{\"id\":2,\"name\":\"ar\"},\"publishTime\":10,\"size\":3,\"company\":\"c\"}]}}"));

        var albums = await api.SearchAlbumsAsync("hi");

        Assert.Single(albums);
        Assert.Equal(2, albums[0].Artist!.Id);
        Assert.Equal("c", albums[0].Company);
        Assert.Equal(3, albums[0].Size);
    }

    [Fact]
    public async Task SearchArtists_ParsesAlias()
    {
        var api = new SearchApi(Client("/api/cloudsearch/pc",
            "{\"result\":{\"artists\":[{\"id\":1,\"name\":\"a\",\"picUrl\":\"p\",\"alias\":[\"x\",\"y\"],\"albumSize\":2,\"musicSize\":3}]}}"));

        var artists = await api.SearchArtistsAsync("hi");

        Assert.Single(artists);
        Assert.Equal(new[] { "x", "y" }, artists[0].Alias);
        Assert.Equal(3, artists[0].MusicSize);
    }

    [Fact]
    public async Task Search_MissingResult_ReturnsEmpty()
    {
        var api = new SearchApi(Client("/api/cloudsearch/pc", "{\"code\":200}"));

        Assert.Empty(await api.SearchSongsAsync("hi"));
        Assert.Empty(await api.SearchAlbumsAsync("hi"));
        Assert.Empty(await api.SearchArtistsAsync("hi"));
    }

    [Fact]
    public async Task SongDetail_ReturnsSongs()
    {
        var api = new SongApi(Client("/api/v3/song/detail",
            "{\"songs\":[{\"id\":7,\"name\":\"n\",\"ar\":[{\"name\":\"a\"}],\"al\":{\"name\":\"al\"},\"dt\":555}]}"));

        var songs = await api.GetSongDetailAsync(new long[] { 7 });

        Assert.Single(songs);
        Assert.Equal(555, songs[0].Duration);
    }

    [Fact]
    public async Task SongDetail_EmptyIds_NoRequest()
    {
        var handler = new Router(_ => throw new InvalidOperationException("不该发请求"));
        var api = new SongApi(new NcmHttp(handler));

        Assert.Empty(await api.GetSongDetailAsync(System.Array.Empty<long>()));
    }

    [Fact]
    public async Task Lyric_ReturnsBothTexts()
    {
        var api = new SongApi(Client("/api/song/lyric",
            "{\"lrc\":{\"lyric\":\"[00:01.00]a\"},\"tlyric\":{\"lyric\":\"[00:01.00]b\"}}"));

        var lyrics = await api.GetLyricAsync(1);

        Assert.Equal("[00:01.00]a", lyrics.Lrc);
        Assert.Equal("[00:01.00]b", lyrics.Translation);
    }

    [Fact]
    public async Task PlaylistDetail_OrdersByTrackIds_AndFetchesMissing()
    {
        var handler = new Router(path => path == "/eapi/v6/playlist/detail"
            ? Json("{\"code\":200,\"playlist\":{\"tracks\":[{\"id\":1,\"name\":\"one\"}],\"trackIds\":[{\"id\":1},{\"id\":2}]}}")
            : Json("{\"songs\":[{\"id\":2,\"name\":\"two\"}]}"));
        var api = new PlaylistApi(new NcmHttp(handler));

        var songs = await api.GetPlaylistDetailAsync(9);

        Assert.Equal(new long[] { 1, 2 }, songs.Select(s => s.Id).ToArray());
        Assert.Equal("one", songs[0].Name);
        Assert.Equal("two", songs[1].Name);
    }

    [Fact]
    public async Task PlaylistDetail_Non200_Throws()
    {
        var api = new PlaylistApi(Client("/eapi/v6/playlist/detail", "{\"code\":404}"));

        await Assert.ThrowsAsync<NcmApiException>(() => api.GetPlaylistDetailAsync(9));
    }

    private static NcmHttp Client(string path, string body) => new(new Router(p => p == path ? Json(body) : Json("{}")));

    private static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class Router : HttpMessageHandler
    {
        private readonly Func<string, HttpResponseMessage> _respond;

        public Router(Func<string, HttpResponseMessage> respond) => _respond = respond;

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            Task.FromResult(_respond(request.RequestUri!.AbsolutePath));
    }
}

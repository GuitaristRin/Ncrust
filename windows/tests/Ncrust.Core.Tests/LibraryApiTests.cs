using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Net;
using Xunit;

namespace Ncrust.Core.Tests;

public class LibraryApiTests
{
    [Theory]
    [InlineData("{\"code\":200}", true)]
    [InlineData("{\"code\":404}", false)]
    [InlineData("not json", false)]
    public async Task LikeSong_ChecksBusinessCode(string body, bool expected)
    {
        var api = new LibraryApi(Router("/eapi/radio/like", body));

        Assert.Equal(expected, await api.LikeSongAsync(123, like: true));
    }

    [Fact]
    public async Task SubscribeAlbum_UsesSubAndUnsubPaths()
    {
        var seen = new List<string>();
        var handler = new Handler(path =>
        {
            seen.Add(path);
            return Json("{\"code\":200}");
        });
        var api = new LibraryApi(new NcmHttp(handler));

        Assert.True(await api.SubscribeAlbumAsync(9, subscribe: true));
        Assert.True(await api.SubscribeAlbumAsync(9, subscribe: false));
        Assert.Equal(new[] { "/eapi/album/sub", "/eapi/album/unsub" }, seen);
    }

    [Fact]
    public async Task PlaylistTrackIds_ReturnsOrderedIds()
    {
        var api = new PlaylistApi(Router("/eapi/v6/playlist/detail",
            "{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":1},{\"id\":2},{\"id\":3}]}}"));

        var ids = await api.GetPlaylistTrackIdsAsync(9);

        Assert.Equal(new long[] { 1, 2, 3 }, ids);
    }

    [Fact]
    public async Task PlaylistTrackIds_Non200_Throws()
    {
        var api = new PlaylistApi(Router("/eapi/v6/playlist/detail", "{\"code\":404}"));

        await Assert.ThrowsAsync<NcmApiException>(() => api.GetPlaylistTrackIdsAsync(9));
    }

    [Fact]
    public async Task SubscribedAlbums_ParsesArtistAndCount()
    {
        var api = new AlbumApi(Router("/eapi/album/sublist",
            "{\"code\":200,\"data\":[{\"id\":1,\"name\":\"a\",\"picUrl\":\"p\",\"artists\":[{\"name\":\"ar\"}],\"size\":12}]}"));

        var albums = await api.GetSubscribedAlbumsAsync();

        Assert.Single(albums);
        Assert.Equal(1, albums[0].AlbumId);
        Assert.Equal("ar", albums[0].Artist);
        Assert.Equal(12, albums[0].SongCount);
    }

    private static NcmHttp Router(string path, string body) =>
        new(new Handler(p => p == path ? Json(body) : Json("{}")));

    private static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class Handler : HttpMessageHandler
    {
        private readonly System.Func<string, HttpResponseMessage> _respond;

        public Handler(System.Func<string, HttpResponseMessage> respond) => _respond = respond;

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            Task.FromResult(_respond(request.RequestUri!.AbsolutePath));
    }
}

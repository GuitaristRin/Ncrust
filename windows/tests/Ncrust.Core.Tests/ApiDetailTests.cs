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

public class ApiDetailTests
{
    [Fact]
    public async Task AlbumDetail_ParsesAlbumAndSongs()
    {
        var api = new AlbumApi(Router("/api/v1/album/5",
            "{\"code\":200,\"album\":{\"id\":5,\"name\":\"A\",\"picUrl\":\"p\",\"artist\":{\"id\":1,\"name\":\"ar\"},\"size\":3},\"songs\":[{\"id\":1,\"name\":\"s\"}]}"));

        var result = await api.GetAlbumDetailAsync(5);

        Assert.Equal("A", result.Album!.Name);
        Assert.Equal(1, result.Album.Artist!.Id);
        Assert.Single(result.Songs);
        Assert.Equal("s", result.Songs[0].Name);
    }

    [Fact]
    public async Task ArtistAlbums_ParsesArtistAndHotAlbums()
    {
        var api = new ArtistApi(Router("/api/artist/albums/7",
            "{\"code\":200,\"artist\":{\"id\":7,\"name\":\"ar\",\"albumSize\":9},\"hotAlbums\":[{\"id\":1,\"name\":\"al\",\"picUrl\":\"p\",\"size\":2}],\"more\":true}"));

        var result = await api.GetArtistAlbumsAsync(7);

        Assert.Equal(9, result.Artist!.AlbumSize);
        Assert.True(result.More);
        Assert.Single(result.Albums);
        Assert.Equal(2, result.Albums[0].Size);
    }

    [Fact]
    public async Task Profile_ReadsProfileThenAccountFallback()
    {
        var api = new AccountApi(Router("/eapi/w/nuser/account/get",
            "{\"code\":200,\"account\":{\"id\":1,\"userName\":\"acc\"},\"profile\":{\"userId\":1,\"nickname\":\"nick\",\"avatarUrl\":\"a\"}}"));

        var profile = await api.GetProfileAsync();

        Assert.Equal(1, profile!.UserId);
        Assert.Equal("nick", profile.Nickname);
        Assert.Equal("a", profile.AvatarUrl);
        Assert.Equal(1, await api.GetCurrentUserIdAsync());
    }

    [Fact]
    public async Task Profile_Unauthenticated_ReturnsNull()
    {
        var api = new AccountApi(Router("/eapi/w/nuser/account/get", "{\"code\":301}"));

        Assert.Null(await api.GetProfileAsync());
        Assert.Equal(0, await api.GetCurrentUserIdAsync());
    }

    [Fact]
    public async Task UserPlaylists_ParsesAndFindsLiked()
    {
        var api = new AccountApi(Router("/eapi/user/playlist",
            "{\"code\":200,\"playlist\":[" +
            "{\"id\":1,\"name\":\"自建\",\"coverImgUrl\":\"c\",\"trackCount\":3,\"specialType\":0,\"creator\":{\"userId\":9}}," +
            "{\"id\":2,\"name\":\"我喜欢的音乐\",\"trackCount\":5,\"specialType\":5}]}"));

        var playlists = await api.GetUserPlaylistsAsync(9);

        Assert.Equal(2, playlists.Count);
        Assert.Equal(9, playlists[0].CreatorUserId);
        Assert.Equal(2, await api.GetLikedPlaylistIdAsync(9));
    }

    [Fact]
    public async Task UserPlaylists_Non200_Throws()
    {
        var api = new AccountApi(Router("/eapi/user/playlist", "{\"code\":301}"));

        await Assert.ThrowsAsync<NcmApiException>(() => api.GetUserPlaylistsAsync(9));
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

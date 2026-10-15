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
    public async Task LikedPlaylist_PrefersNameOverOtherSpecialPlaylists()
    {
        var api = new AccountApi(Router("/eapi/user/playlist",
            "{\"code\":200,\"playlist\":[" +
            "{\"id\":7,\"name\":\"漫游\",\"specialType\":1}," +
            "{\"id\":2,\"name\":\"我喜欢的音乐\",\"trackCount\":5,\"specialType\":5}]}"));

        Assert.Equal(2, await api.GetLikedPlaylistIdAsync(9));
    }

    [Fact]
    public async Task LikedPlaylist_FallsBackToSpecialType5()
    {
        var api = new AccountApi(Router("/eapi/user/playlist",
            "{\"code\":200,\"playlist\":[" +
            "{\"id\":7,\"name\":\"My Favorites\",\"specialType\":1}," +
            "{\"id\":3,\"name\":\"Liked\",\"specialType\":5}]}"));

        Assert.Equal(3, await api.GetLikedPlaylistIdAsync(9));
    }

    [Fact]
    public async Task UserPlaylists_Non200_Throws()
    {
        var api = new AccountApi(Router("/eapi/user/playlist", "{\"code\":301}"));

        await Assert.ThrowsAsync<NcmApiException>(() => api.GetUserPlaylistsAsync(9));
    }

    /// <summary>
    /// song/detail 把 <c>songs[]</c> 与 <c>privileges[]</c> 作为两个并列数组返回，得按 id 对上：
    /// 取链判定「无版权」只看 <c>privilege.st</c>，对不上就只能说「暂时无法播放」。
    /// </summary>
    [Fact]
    public async Task SongDetail_AttachesPrivilegesById()
    {
        var api = new SongApi(Router("/api/v3/song/detail",
            "{\"code\":200,\"songs\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]," +
            "\"privileges\":[{\"id\":2,\"st\":-200,\"pl\":0,\"fee\":1},{\"id\":1,\"st\":0,\"pl\":320000,\"fee\":8}]}"));

        var songs = await api.GetSongDetailAsync(new long[] { 1, 2 });

        var first = Assert.IsType<Ncrust.Core.Account.SongPrivilege>(songs[0].Privilege);
        var second = Assert.IsType<Ncrust.Core.Account.SongPrivilege>(songs[1].Privilege);
        Assert.Equal(320000, first.Pl);
        Assert.Equal(-200, second.St);
        Assert.Equal(1, second.Fee);
    }

    /// <summary>
    /// 会话判定只认「读到业务码的地方」：account/get 与 vip/info 回 301 必须报到请求层，
    /// 否则登录失效时（account/get 照样 200，只有需登录接口回 301）界面永远等不到失效横幅。
    /// </summary>
    [Fact]
    public async Task SessionEndpoints_Report301ToRequestLayer()
    {
        var http = Router("/eapi/w/nuser/account/get", "{\"code\":301,\"message\":\"用户未登录\"}");
        var codes = new System.Collections.Generic.List<int>();
        http.BusinessCode += codes.Add;

        var account = new AccountApi(http);
        Assert.NotNull(await account.GetAccountAsync()); // 301 也是「拿到响应」，判定交给上层。
        Assert.Equal(new[] { 301 }, codes);

        var vipHttp = Router("/weapi/music-vip-membership/client/vip/info", "{\"code\":301,\"message\":\"用户未登录\"}");
        var vipCodes = new System.Collections.Generic.List<int>();
        vipHttp.BusinessCode += vipCodes.Add;

        var vip = new AccountApi(vipHttp);
        Assert.NotNull(await vip.GetVipInfoAsync(1));
        Assert.Equal(new[] { 301 }, vipCodes);
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

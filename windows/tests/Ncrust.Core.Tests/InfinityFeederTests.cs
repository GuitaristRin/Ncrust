using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Net;
using Ncrust.Core.Playback;
using Xunit;

namespace Ncrust.Core.Tests;

public class InfinityFeederTests
{
    private static IReadOnlyList<SongItem> Songs(params long[] ids) =>
        ids.Select(id => new SongItem { Id = id, Name = "s" + id }).ToList();

    private static Func<CancellationToken, Task<IReadOnlyList<SongItem>>> Returns(IReadOnlyList<SongItem> songs) =>
        _ => Task.FromResult(songs);

    private static Func<CancellationToken, Task<IReadOnlyList<SongItem>>> Throws() =>
        _ => throw new HttpRequestException("boom");

    [Fact]
    public async Task FmMode_UsesPersonalFm_AndFiltersQueued()
    {
        long? similarSeed = null;
        var feeder = new InfinityFeeder(
            Returns(Songs(1, 2, 3)),
            (seed, _) => { similarSeed = seed; return Task.FromResult(Songs(9)); },
            Returns(Songs(7)));

        var result = await feeder.FetchAsync(fmMode: true, seedSongId: 5, existingIds: new long[] { 2 });

        Assert.Equal(new long[] { 1, 3 }, result.Select(s => s.Id));
        Assert.Null(similarSeed);
    }

    [Fact]
    public async Task SimilarMode_PassesSeed()
    {
        long? similarSeed = null;
        var feeder = new InfinityFeeder(
            Returns(Songs(1)),
            (seed, _) => { similarSeed = seed; return Task.FromResult(Songs(8, 9)); },
            Returns(Songs(7)));

        var result = await feeder.FetchAsync(fmMode: false, seedSongId: 42, existingIds: Array.Empty<long>());

        Assert.Equal(42, similarSeed);
        Assert.Equal(new long[] { 8, 9 }, result.Select(s => s.Id));
    }

    [Fact]
    public async Task EmptyAfterFilter_FallsBackToDaily()
    {
        var feeder = new InfinityFeeder(
            Returns(Songs(1)),
            (_, __) => Task.FromResult(Songs(1, 2)),
            Returns(Songs(2, 3, 4)));

        var result = await feeder.FetchAsync(fmMode: false, seedSongId: 1, existingIds: new long[] { 1, 2 });

        Assert.Equal(new long[] { 3, 4 }, result.Select(s => s.Id));
    }

    [Fact]
    public async Task PrimaryFailure_FallsBackToDaily_AndDailyFailureIsEmpty()
    {
        var feeder = new InfinityFeeder(Throws(), (_, __) => throw new HttpRequestException(), Returns(Songs(5)));
        Assert.Equal(new long[] { 5 }, (await feeder.FetchAsync(true, 1, Array.Empty<long>())).Select(s => s.Id));

        var broken = new InfinityFeeder(Throws(), (_, __) => throw new HttpRequestException(), Throws());
        Assert.Empty(await broken.FetchAsync(false, 1, Array.Empty<long>()));
    }

    [Fact]
    public async Task Cancellation_Propagates()
    {
        var feeder = new InfinityFeeder(
            _ => throw new OperationCanceledException(),
            (_, __) => Task.FromResult(Songs(1)),
            Returns(Songs(2)));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => feeder.FetchAsync(true, 1, Array.Empty<long>()));
    }

    [Fact]
    public void Filter_DropsDuplicatesWithinBatch_AndInvalidIds()
    {
        var result = InfinityFeeder.Filter(Songs(3, 0, 3, 4), new HashSet<long> { 4 });

        Assert.Equal(new long[] { 3 }, result.Select(s => s.Id));
    }

    [Fact]
    public async Task SimilarSongs_EapiFirst_ThenWeapiFallback()
    {
        var calls = new List<string>();
        var handler = new FakeHandler(request =>
        {
            var uri = request.RequestUri!.ToString();
            calls.Add(uri);
            return uri.Contains("/eapi/")
                ? Json("{\"code\":200,\"songs\":[]}")
                : Json("{\"code\":200,\"songs\":[{\"id\":11,\"name\":\"x\",\"artists\":[{\"name\":\"A\"}],\"album\":{\"id\":3,\"name\":\"al\",\"picUrl\":\"p\"},\"duration\":1000}]}");
        });
        var api = new DiscoveryApi(new NcmHttp(handler));

        var songs = await api.GetSimilarSongsAsync(99);

        Assert.Equal(2, calls.Count);
        Assert.EndsWith("/eapi/v1/discovery/similarSong", calls[0]);
        Assert.EndsWith("/weapi/discovery/simiSong", calls[1]);
        Assert.Single(songs);
        Assert.Equal(11, songs[0].Id);
        Assert.Equal("al", songs[0].Album!.Name);
    }

    [Fact]
    public async Task SimilarSongs_BothPathsFail_ReturnsEmpty()
    {
        var api = new DiscoveryApi(new NcmHttp(new FakeHandler(_ => throw new HttpRequestException("offline"))));

        Assert.Empty(await api.GetSimilarSongsAsync(1));
    }

    private static HttpResponseMessage Json(string body) => new HttpResponseMessage(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _respond;

        public FakeHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) => _respond = respond;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(_respond(request));
    }
}

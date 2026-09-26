using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Library;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary><see cref="ILibraryCloud"/> 的真实实现：把各 Api 类接到 <c>LibraryManager</c> 上。</summary>
    public sealed class ApiLibraryCloud : ILibraryCloud
    {
        private readonly AccountApi _account;
        private readonly PlaylistApi _playlists;
        private readonly SongApi _songs;
        private readonly AlbumApi _albums;
        private readonly LibraryApi _library;

        private long _likedPlaylistId;

        public ApiLibraryCloud(NcmHttp http)
        {
            if (http == null)
            {
                throw new ArgumentNullException(nameof(http));
            }

            _account = new AccountApi(http);
            _playlists = new PlaylistApi(http);
            _songs = new SongApi(http);
            _albums = new AlbumApi(http);
            _library = new LibraryApi(http);
        }

        public Task<long> GetCurrentUserIdAsync(CancellationToken cancellationToken) =>
            _account.GetCurrentUserIdAsync(cancellationToken);

        public async Task<LikedIdsResult> GetLikedTrackIdsAsync(long uid, CancellationToken cancellationToken)
        {
            try
            {
                if (_likedPlaylistId == 0)
                {
                    _likedPlaylistId = await _account.GetLikedPlaylistIdAsync(uid, cancellationToken).ConfigureAwait(false) ?? 0;
                }

                if (_likedPlaylistId == 0)
                {
                    return LikedIdsResult.Failure("liked playlist not found");
                }

                var ids = await _playlists.GetPlaylistTrackIdsAsync(_likedPlaylistId, cancellationToken).ConfigureAwait(false);
                return LikedIdsResult.Success(ids);
            }
            catch (Exception ex)
            {
                return LikedIdsResult.Failure(ex.Message);
            }
        }

        public Task<IReadOnlyList<SongItem>> GetSongsByIdsAsync(
            IReadOnlyList<long> ids,
            CancellationToken cancellationToken) =>
            _songs.GetSongDetailAsync(ids, cancellationToken);

        public Task<IReadOnlyList<CloudAlbum>> GetSubscribedAlbumsAsync(CancellationToken cancellationToken) =>
            _albums.GetSubscribedAlbumsAsync(100, 0, cancellationToken);

        public Task<bool> LikeSongAsync(long songId, bool like, CancellationToken cancellationToken) =>
            _library.LikeSongAsync(songId, like, cancellationToken);

        public Task<bool> SubscribeAlbumAsync(long albumId, bool subscribe, CancellationToken cancellationToken) =>
            _library.SubscribeAlbumAsync(albumId, subscribe, cancellationToken);
    }
}

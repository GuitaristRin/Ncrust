using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Json;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Playback
{
    /// <summary>持久化的播放状态：队列 + 当前索引。</summary>
    public sealed class SavedPlaybackState
    {
        public SavedPlaybackState(IReadOnlyList<SongItem> queue, int index)
        {
            Queue = queue;
            Index = index;
        }

        public IReadOnlyList<SongItem> Queue { get; }

        public int Index { get; }
    }

    /// <summary>
    /// 播放状态持久化（对应 Android <c>PlaybackStateManager</c>）：把队列与当前索引写进
    /// <c>playback_state.json</c>，进程被杀后恢复。
    /// </summary>
    public sealed class PlaybackStateStore
    {
        private const string FileName = "playback_state.json";

        private readonly IFileStore _files;

        public PlaybackStateStore(IFileStore files) =>
            _files = files ?? throw new ArgumentNullException(nameof(files));

        public Task SaveAsync(IReadOnlyList<SongItem> queue, int index, CancellationToken cancellationToken = default)
        {
            var json = "{\"index\":" + index + ",\"queue\":" + SongJson.Write(queue) + "}";
            return _files.WriteTextAsync(FileName, json);
        }

        public async Task<SavedPlaybackState?> LoadAsync(CancellationToken cancellationToken = default)
        {
            var raw = await _files.ReadTextAsync(FileName).ConfigureAwait(false);
            if (string.IsNullOrEmpty(raw))
            {
                return null;
            }

            JsonValue json;
            try
            {
                json = JsonValue.Parse(raw!);
            }
            catch (JsonParseException)
            {
                return null;
            }

            var queue = SongJson.ReadArray(json.GetArray("queue"));
            if (queue.Count == 0)
            {
                return null;
            }

            return new SavedPlaybackState(queue, json.GetInt("index", 0));
        }
    }
}

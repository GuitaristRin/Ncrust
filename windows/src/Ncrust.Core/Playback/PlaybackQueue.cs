using System;
using System.Collections.Generic;
using System.Linq;
using Ncrust.Core.Api;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// 播放队列状态机（对应 Android <c>MainScreen</c> 里的队列函数，收拢成可测试的单元）。
    /// 只决定「队列长什么样、当前是谁」，不含任何播放器依赖。
    ///
    /// 关键不变量：<c>Songs[CurrentIndex]</c> 恒等于正在播放的歌。去重操作先记当前歌 id、
    /// 过滤后重新定位索引；SHUFFLE 模式下任何修改都重建打乱表。
    ///
    /// 与 Android 的唯一有意偏差：<see cref="RemoveAt"/> 删除当前曲**之前**的歌时按身份
    /// 重新定位当前索引（Android 现行实现只做 clamp，会错位）。见
    /// <c>spec/fixtures/queue/README.md</c> 的「remove 的规格修正」。
    /// </summary>
    public sealed class PlaybackQueue
    {
        private readonly List<SongItem> _songs = new List<SongItem>();
        private readonly List<int> _shuffledIndices = new List<int>();
        private readonly Random _random = new Random();

        private int _shuffledPosition;

        public PlaybackMode Mode { get; private set; } = PlaybackMode.Cycle;

        public IReadOnlyList<SongItem> Songs => _songs;

        /// <summary>无当前时为 -1。</summary>
        public int CurrentIndex { get; private set; } = -1;

        public SongItem? Current =>
            CurrentIndex >= 0 && CurrentIndex < _songs.Count ? _songs[CurrentIndex] : null;

        public IReadOnlyList<int> ShuffledIndices => _shuffledIndices;

        public int ShuffledPosition => _shuffledPosition;

        public void SetMode(PlaybackMode mode)
        {
            Mode = mode;
            if (mode == PlaybackMode.Shuffle)
            {
                RegenerateShuffle();
            }
            else
            {
                _shuffledIndices.Clear();
                _shuffledPosition = 0;
            }
        }

        /// <summary>整队替换，当前置 0（无歌则 -1）。</summary>
        public void ReplaceAll(IReadOnlyList<SongItem> songs)
        {
            _songs.Clear();
            _songs.AddRange(songs);
            CurrentIndex = _songs.Count > 0 ? 0 : -1;
            AfterMutation();
        }

        /// <summary>去重后插到当前歌的下一首；当前歌不变。</summary>
        public void InsertNext(SongItem song)
        {
            var currentId = Current?.Id;
            if (song.Id == currentId)
            {
                return;
            }

            var filtered = _songs.Where(item => item.Id != song.Id).ToList();
            var newCurrent = currentId.HasValue ? Math.Max(IndexOfId(filtered, currentId.Value), 0) : -1;
            var insertPosition = Clamp(newCurrent + 1, 0, filtered.Count);
            filtered.Insert(insertPosition, song);

            _songs.Clear();
            _songs.AddRange(filtered);
            CurrentIndex = newCurrent < 0 ? 0 : newCurrent;
            AfterMutation();
        }

        /// <summary>去重后加到队尾；当前歌不变。</summary>
        public void Append(SongItem song)
        {
            var currentId = Current?.Id;
            if (song.Id == currentId)
            {
                return;
            }

            var filtered = _songs.Where(item => item.Id != song.Id).ToList();
            _songs.Clear();
            _songs.AddRange(filtered);
            _songs.Add(song);

            CurrentIndex = currentId.HasValue ? Math.Max(IndexOfId(_songs, currentId.Value), 0) : 0;
            AfterMutation();
        }

        /// <summary>= InsertNext 后把当前切到该曲。</summary>
        public void PlaySong(SongItem song)
        {
            var isQueued = _songs.Count > 0 && CurrentIndex >= 0 && CurrentIndex < _songs.Count;
            if (!isQueued)
            {
                _songs.Clear();
                _songs.Add(song);
                CurrentIndex = 0;
                AfterMutation();
                return;
            }

            var currentId = Current?.Id;
            if (song.Id != currentId)
            {
                var filtered = _songs.Where(item => item.Id != song.Id).ToList();
                var newCurrent = currentId.HasValue ? Math.Max(IndexOfId(filtered, currentId.Value), 0) : -1;
                var insertPosition = Clamp(newCurrent + 1, 0, filtered.Count);
                filtered.Insert(insertPosition, song);
                _songs.Clear();
                _songs.AddRange(filtered);
                CurrentIndex = newCurrent < 0 ? 0 : newCurrent;
                AfterMutation();
            }

            var index = IndexOfId(_songs, song.Id);
            if (index >= 0)
            {
                CurrentIndex = index;
            }
        }

        /// <summary>去重后整批插到当前歌之后；当前歌不变。无当前时等价 ReplaceAll。</summary>
        public void InsertAllNext(IReadOnlyList<SongItem> songs)
        {
            if (songs.Count == 0)
            {
                return;
            }

            if (CurrentIndex < 0)
            {
                ReplaceAll(songs);
                return;
            }

            var currentId = Current?.Id;
            var toInsert = currentId.HasValue
                ? songs.Where(song => song.Id != currentId.Value).ToList()
                : songs.ToList();
            if (toInsert.Count == 0)
            {
                return;
            }

            var ids = new HashSet<long>(toInsert.Select(song => song.Id));
            var filtered = _songs.Where(song => !ids.Contains(song.Id)).ToList();
            var newCurrent = currentId.HasValue ? Math.Max(IndexOfId(filtered, currentId.Value), 0) : 0;
            var insertPosition = Clamp(newCurrent + 1, 0, filtered.Count);
            filtered.InsertRange(insertPosition, toInsert);

            _songs.Clear();
            _songs.AddRange(filtered);
            CurrentIndex = newCurrent;
            AfterMutation();
        }

        /// <summary>只追加队列里没有的；当前歌不变。无当前时等价 ReplaceAll。</summary>
        public void AppendAll(IReadOnlyList<SongItem> songs)
        {
            if (songs.Count == 0)
            {
                return;
            }

            if (CurrentIndex < 0)
            {
                ReplaceAll(songs);
                return;
            }

            var existingIds = new HashSet<long>(_songs.Select(song => song.Id));
            var newSongs = songs.Where(song => !existingIds.Contains(song.Id)).ToList();
            if (newSongs.Count == 0)
            {
                return;
            }

            _songs.AddRange(newSongs);
            AfterMutation();
        }

        /// <summary>
        /// 删除指定索引。与 Android 现行 clamp 不同：删当前曲之前的歌时，当前索引跟随
        /// 当前歌的新位置（保持不变量）。
        /// </summary>
        public void RemoveAt(int index)
        {
            if (index < 0 || index >= _songs.Count)
            {
                return;
            }

            var currentId = Current?.Id;
            _songs.RemoveAt(index);

            if (_songs.Count == 0)
            {
                CurrentIndex = -1;
            }
            else if (currentId.HasValue)
            {
                var found = IndexOfId(_songs, currentId.Value);
                CurrentIndex = found >= 0 ? found : Clamp(index, 0, _songs.Count - 1);
            }
            else
            {
                CurrentIndex = Clamp(CurrentIndex, 0, _songs.Count - 1);
            }

            AfterMutation();
        }

        /// <summary>拖拽排序，当前项跟随其歌曲移动。</summary>
        public void Move(int from, int to)
        {
            if (from < 0 || from >= _songs.Count || to < 0 || to >= _songs.Count || from == to)
            {
                return;
            }

            var song = _songs[from];
            _songs.RemoveAt(from);
            _songs.Insert(to, song);

            CurrentIndex = from == CurrentIndex ? to
                : from < CurrentIndex && to >= CurrentIndex ? CurrentIndex - 1
                : from > CurrentIndex && to <= CurrentIndex ? CurrentIndex + 1
                : CurrentIndex;

            AfterMutation();
        }

        /// <summary>直接切当前到该索引（对应 playFromQueue）。越界忽略。</summary>
        public void SetCurrentIndex(int index)
        {
            if (index >= 0 && index < _songs.Count)
            {
                CurrentIndex = index;
            }
        }

        /// <summary>
        /// 预载 / 衔接用的「下一首」（不改状态）。SINGLE 返回当前曲（无缝单曲循环）；CYCLE 队尾回绕队首，
        /// 单曲队列返回 null；SHUFFLE 在一轮内前进、队尾回绕到打乱表首位；LINE / INFINITY 到队尾返回 null。
        /// </summary>
        public SongItem? PeekNext()
        {
            if (_songs.Count == 0 || CurrentIndex < 0)
            {
                return null;
            }

            switch (Mode)
            {
                case PlaybackMode.Single:
                    return Current;
                case PlaybackMode.Shuffle:
                    if (_shuffledIndices.Count == 0)
                    {
                        return null;
                    }

                    var nextPosition = _shuffledPosition < _shuffledIndices.Count - 1 ? _shuffledPosition + 1 : 0;
                    return SongAt(_shuffledIndices[nextPosition]);
                case PlaybackMode.Line:
                case PlaybackMode.Infinity:
                    return CurrentIndex < _songs.Count - 1 ? _songs[CurrentIndex + 1] : null;
                default:
                    if (_songs.Count <= 1)
                    {
                        return null;
                    }

                    return CurrentIndex < _songs.Count - 1 ? _songs[CurrentIndex + 1] : _songs[0];
            }
        }

        /// <summary>「上一首」（不改状态）。SINGLE 返回当前曲；SHUFFLE 首项返回 null；INFINITY 不可回退。</summary>
        public SongItem? PeekPrevious()
        {
            if (_songs.Count == 0 || CurrentIndex < 0)
            {
                return null;
            }

            switch (Mode)
            {
                case PlaybackMode.Infinity:
                    return null;
                case PlaybackMode.Single:
                    return Current;
                case PlaybackMode.Shuffle:
                    return _shuffledPosition > 0 ? SongAt(_shuffledIndices[_shuffledPosition - 1]) : null;
                default:
                    return CurrentIndex > 0 ? _songs[CurrentIndex - 1] : _songs[_songs.Count - 1];
            }
        }

        /// <summary>按当前歌在新队列中的位置重建打乱表；首项恒为当前索引。</summary>
        public void RegenerateShuffle()
        {
            _shuffledIndices.Clear();
            _shuffledPosition = 0;
            if (_songs.Count == 0)
            {
                return;
            }

            var current = Clamp(CurrentIndex, 0, _songs.Count - 1);
            var others = Enumerable.Range(0, _songs.Count).Where(i => i != current).ToList();
            Shuffle(others);
            _shuffledIndices.Add(current);
            _shuffledIndices.AddRange(others);
        }

        private void AfterMutation()
        {
            if (Mode == PlaybackMode.Shuffle)
            {
                RegenerateShuffle();
            }
        }

        private void Shuffle(List<int> values)
        {
            for (var i = values.Count - 1; i > 0; i--)
            {
                var j = _random.Next(i + 1);
                (values[i], values[j]) = (values[j], values[i]);
            }
        }

        private SongItem? SongAt(int index) => index >= 0 && index < _songs.Count ? _songs[index] : null;

        private static int Clamp(int value, int min, int max) =>
            value < min ? min : value > max ? max : value;

        private static int IndexOfId(IReadOnlyList<SongItem> songs, long id)
        {
            for (var i = 0; i < songs.Count; i++)
            {
                if (songs[i].Id == id)
                {
                    return i;
                }
            }

            return -1;
        }
    }
}

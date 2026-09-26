using System;
using System.Collections.Generic;

namespace Ncrust.Core.Cache
{
    /// <summary>
    /// 固定容量的 LRU（对应 Android 的 <c>androidx.collection.LruCache</c>）。
    /// Get 会把命中项移到最前；超出容量淘汰最久未使用项。
    /// </summary>
    public sealed class LruCache<TKey, TValue> where TKey : notnull
    {
        private readonly int _capacity;
        private readonly Dictionary<TKey, LinkedListNode<KeyValuePair<TKey, TValue>>> _map;
        private readonly LinkedList<KeyValuePair<TKey, TValue>> _list = new LinkedList<KeyValuePair<TKey, TValue>>();

        public LruCache(int capacity)
        {
            if (capacity <= 0)
            {
                throw new ArgumentOutOfRangeException(nameof(capacity));
            }

            _capacity = capacity;
            _map = new Dictionary<TKey, LinkedListNode<KeyValuePair<TKey, TValue>>>();
        }

        public int Count => _map.Count;

        public bool TryGet(TKey key, out TValue value)
        {
            if (_map.TryGetValue(key, out var node))
            {
                _list.Remove(node);
                _list.AddFirst(node);
                value = node.Value.Value;
                return true;
            }

            value = default!;
            return false;
        }

        public void Put(TKey key, TValue value)
        {
            if (_map.TryGetValue(key, out var existing))
            {
                _list.Remove(existing);
                existing.Value = new KeyValuePair<TKey, TValue>(key, value);
                _list.AddFirst(existing);
                return;
            }

            var node = _list.AddFirst(new KeyValuePair<TKey, TValue>(key, value));
            _map[key] = node;

            if (_map.Count > _capacity)
            {
                var last = _list.Last!;
                _list.RemoveLast();
                _map.Remove(last.Value.Key);
            }
        }

        public void Clear()
        {
            _map.Clear();
            _list.Clear();
        }
    }
}

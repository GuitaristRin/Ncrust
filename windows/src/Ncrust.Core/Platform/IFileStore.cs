using System.Threading.Tasks;

namespace Ncrust.Core.Platform
{
    /// <summary>
    /// 应用私有目录下的文本文件读写，用于播放状态、音乐库、歌词缓存、搜索历史等较大的 JSON 数据。
    /// <paramref name="name"/> 是不含目录的文件名，例如 <c>playback_state.json</c>。
    /// </summary>
    public interface IFileStore
    {
        /// <summary>文件不存在时返回 null。</summary>
        Task<string?> ReadTextAsync(string name);

        /// <summary>整体覆盖写入。</summary>
        Task WriteTextAsync(string name, string content);

        /// <summary>文件不存在时不报错。</summary>
        Task DeleteAsync(string name);
    }
}

using System;
using System.IO;
using System.Threading.Tasks;
using Ncrust.Core.Platform;
using Windows.Storage;

namespace Ncrust.Platform
{
    /// <summary>
    /// <see cref="IFileStore"/> 的 UWP 实现：<c>ApplicationData.Current.LocalFolder</c> 下的文本文件。
    /// 队列、音乐库、歌词缓存、搜索历史这些较大的 JSON 都走这里。
    /// </summary>
    public sealed class LocalFileStore : IFileStore
    {
        public async Task<string> ReadTextAsync(string name)
        {
            try
            {
                var file = await ApplicationData.Current.LocalFolder.GetFileAsync(name);
                return await FileIO.ReadTextAsync(file);
            }
            catch (FileNotFoundException)
            {
                return null;
            }
        }

        public async Task WriteTextAsync(string name, string content)
        {
            var file = await ApplicationData.Current.LocalFolder
                .CreateFileAsync(name, CreationCollisionOption.ReplaceExisting);
            await FileIO.WriteTextAsync(file, content);
        }

        public async Task DeleteAsync(string name)
        {
            try
            {
                var file = await ApplicationData.Current.LocalFolder.GetFileAsync(name);
                await file.DeleteAsync();
            }
            catch (FileNotFoundException)
            {
                // 文件不存在时按成功处理。
            }
        }
    }
}

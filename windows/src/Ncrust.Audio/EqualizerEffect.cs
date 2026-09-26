using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Ncrust.Core.Audio;
using Windows.Foundation.Collections;
using Windows.Media;
using Windows.Media.Effects;
using Windows.Media.MediaProperties;

namespace Ncrust.Audio
{
    /// <summary>
    /// 10 段均衡器音效，挂在 MediaPlayer 上（<c>MediaPlayer.AddAudioEffect</c>）。
    ///
    /// 参数经 <see cref="SetProperties"/> 传入的 PropertySet 按引用共享（键见 <see cref="EqualizerEffectKeys"/>）：
    /// 应用改值 → MapChanged → 在调用方线程上重算系数（<see cref="EqualizerProcessor.Configure"/> 线程安全），
    /// 音频线程的 <see cref="ProcessFrame"/> 下一帧就用上新系数。关闭或全 0 dB 时直通，不碰缓冲区。
    /// </summary>
    public sealed class EqualizerEffect : IBasicAudioEffect
    {
        private readonly EqualizerProcessor _processor = new EqualizerProcessor();
        private IPropertySet _configuration;
        private AudioEncodingProperties _encoding;
        private float[] _scratch = new float[0];

        public bool UseInputFrameForOutput => true;

        /// <summary>只接 32 位浮点 PCM：管线负责把解码输出转换成这里列出的格式之一。</summary>
        public IReadOnlyList<AudioEncodingProperties> SupportedEncodingProperties
        {
            get
            {
                var list = new List<AudioEncodingProperties>();
                foreach (var rate in new uint[] { 48000, 44100, 96000, 88200, 192000 })
                {
                    foreach (var channels in new uint[] { 2, 1 })
                    {
                        var properties = AudioEncodingProperties.CreatePcm(rate, channels, 32);
                        properties.Subtype = MediaEncodingSubtypes.Float;
                        list.Add(properties);
                    }
                }

                return list;
            }
        }

        public void SetEncodingProperties(AudioEncodingProperties encodingProperties)
        {
            _encoding = encodingProperties;
            Reconfigure();
        }

        public void SetProperties(IPropertySet configuration)
        {
            _configuration = configuration;
            if (configuration is IObservableMap<string, object> observable)
            {
                observable.MapChanged += (sender, args) => Reconfigure();
            }

            Reconfigure();
        }

        public unsafe void ProcessFrame(ProcessAudioFrameContext context)
        {
            var encoding = _encoding;
            if (!_processor.IsActive || encoding == null)
            {
                return; // 直通：UseInputFrameForOutput 下不改输入帧即原样输出。
            }

            using (var buffer = context.InputFrame.LockBuffer(AudioBufferAccessMode.ReadWrite))
            using (var reference = buffer.CreateReference())
            {
                ((IMemoryBufferByteAccess)reference).GetBuffer(out var data, out _);
                var count = (int)(buffer.Length / sizeof(float));
                if (_scratch.Length < count)
                {
                    _scratch = new float[count];
                }

                Marshal.Copy((IntPtr)data, _scratch, 0, count);
                _processor.Process(_scratch, count, (int)encoding.ChannelCount);
                Marshal.Copy(_scratch, 0, (IntPtr)data, count);
            }
        }

        /// <summary>跳转 / 换曲时丢弃排队的帧：同时清掉滤波器历史，免得把上一段的尾音带进来。</summary>
        public void DiscardQueuedFrames() => _processor.Reset();

        public void Close(MediaEffectClosedReason reason)
        {
        }

        private void Reconfigure()
        {
            var configuration = _configuration;
            var encoding = _encoding;
            if (configuration == null || encoding == null)
            {
                return;
            }

            var enabled = configuration.TryGetValue(EqualizerEffectKeys.Enabled, out var e) && e is bool on && on;
            var preamp = configuration.TryGetValue(EqualizerEffectKeys.PreampDb, out var p) && p is double db ? db : 0;
            var gains = configuration.TryGetValue(EqualizerEffectKeys.GainsDb, out var g) && g is double[] values
                ? values
                : new double[EqualizerBands.Count];
            _processor.Configure(enabled, preamp, gains, encoding.SampleRate);
        }
    }

    /// <summary>取 IMemoryBufferReference 背后原始内存的 COM 接口（音频效果读写缓冲的标准做法）。</summary>
    [ComImport]
    [Guid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal unsafe interface IMemoryBufferByteAccess
    {
        void GetBuffer(out byte* buffer, out uint capacity);
    }
}

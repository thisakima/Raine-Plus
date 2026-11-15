package ru.rainedev.raine.speech;

import io.github.jaredmdobson.concentus.OpusDecoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.jcodec.common.AudioCodecMeta;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.AudioBuffer;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp3.MPEGAudioDemuxer;
import org.jcodec.codecs.mpa.Mp3Decoder;

/**
 * Приводит ответ синтеза к отсчётам, каким бы он ни пришёл.
 * <p>
 * Просить у эндпоинта конкретный формат и верить, что пришёл именно он, —
 * верный способ тихо получить шум: mp3, прочитанный как отсчёты, звучит
 * ровной помехой и уезжает в Telegram как будто так и задумано. Поэтому
 * разбирается то, что реально пришло; всё читается своим: WAV — своим
 * разбором, mp3 — jcodec'ом (он и видео читает), OGG/Opus — concentus'ом,
 * который и кодирует наши голосовые.
 */
public final class Sound {

    private Sound() {}

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Sound.class);

    /**
     * @param body        ответ синтеза
     * @param contentType что эндпоинт назвал в заголовке; может быть пустым
     * @param targetRate  частота, на которой дальше работает кодек
     */
    public static short[] decode(byte[] body, String contentType, int targetRate) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("aac") || type.contains("flac")) {
            throw new IllegalStateException("Синтез вернул " + contentType + " — такой звук читать нечем");
        }
        try {
            if (type.contains("mpeg") || type.contains("mp3") || looksLikeMp3(body)) return mp3(body, targetRate);
            if (type.contains("wav") || isWav(body)) return wav(body, targetRate);
            if (type.contains("ogg") || type.contains("opus") || isOgg(body)) return ogg(body, targetRate);
        } catch (IOException | RuntimeException unsupported) {
            // не разобраться в том, что пришло, — повод прочитать это как
            // отсчёты, а не повод молча отдать шум
            log.warn("Ответ синтеза не распознан ({}), читаю как отсчёты", unsupported.getMessage());
        }
        return pcm(body);
    }

    /** Сырые 16-бит отсчёты — то, что отдаёт OpenAI с response_format=pcm. */
    public static short[] pcm(byte[] body) {
        return Pcm.fromBytes(body, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Разбирает WAV и приводит его к тому, с чем работает кодек: моно и
     * целевая частота. Fish Audio присылает именно такой.
     */
    public static short[] wav(byte[] bytes, int targetRate) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 44
                || buffer.get(0) != 'R' || buffer.get(1) != 'I' || buffer.get(2) != 'F' || buffer.get(3) != 'F'
                || buffer.get(8) != 'W' || buffer.get(9) != 'A' || buffer.get(10) != 'V' || buffer.get(11) != 'E') {
            throw new IllegalStateException("Fish Audio вернул не WAV");
        }

        int channels = 0;
        int sampleRate = 0;
        int dataOffset = 0;
        int dataSize = 0;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String name = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int size = buffer.getInt(offset + 4);
            if (name.equals("fmt ")) {
                int format = buffer.getShort(offset + 8) & 0xFFFF;
                channels = buffer.getShort(offset + 10) & 0xFFFF;
                sampleRate = buffer.getInt(offset + 12);
                int bits = buffer.getShort(offset + 22) & 0xFFFF;
                if (format != 1 || bits != 16) {
                    throw new IllegalStateException(
                            "WAV не 16-бит PCM (format %d, %d бит)".formatted(format, bits));
                }
            } else if (name.equals("data")) {
                dataOffset = offset + 8;
                // Fish отдаёт потоковый WAV: размера заранее не знает и пишет
                // мусор вместо него. Считаем по фактической длине ответа.
                dataSize = bytes.length - dataOffset;
            }
            if (size < 0 || offset + 8 + size > bytes.length) {
                break;
            }
            offset += 8 + size + (size & 1);
        }

        if (channels < 1 || sampleRate < 1 || dataOffset == 0) {
            throw new IllegalStateException("WAV без звуковых данных");
        }
        short[] samples = Pcm.fromBytes(java.util.Arrays.copyOfRange(bytes, dataOffset, dataOffset + dataSize),
                ByteOrder.LITTLE_ENDIAN);
        short[] mono = Pcm.mono(samples, channels);
        return Pcm.resample(mono, sampleRate, targetRate);
    }

    /**
     * mp3 читается jcodec'ом: он уже в проекте ради кадров из видео.
     * Размер кадра заранее неизвестен, поэтому выходной буфер берётся
     * с запасом — самый большой кадр Layer III это 1152 отсчёта.
     */
    public static short[] mp3(byte[] bytes, int targetRate) throws IOException {
        try (ByteBufferSeekableByteChannel channel =
                     ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes))) {
            MPEGAudioDemuxer demuxer = new MPEGAudioDemuxer(channel);
            DemuxerTrack track = demuxer.getAudioTracks().get(0);
            Mp3Decoder decoder = new Mp3Decoder();
            Packet first = track.nextFrame();
            if (first == null) {
                throw new IllegalStateException("mp3 без кадров");
            }
            AudioCodecMeta meta = decoder.getCodecMeta(first.getData().duplicate());
            int rate = meta.getSampleRate();
            int channels = meta.getChannelCount();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Packet packet = first;
            while (packet != null) {
                AudioBuffer frame = decoder.decodeFrame(packet.getData(), ByteBuffer.allocate(65536));
                if (frame != null) {
                    ByteBuffer samples = frame.getData();
                    byte[] chunk = new byte[samples.limit()];
                    System.arraycopy(samples.array(), 0, chunk, 0, chunk.length);
                    out.write(chunk);
                }
                packet = track.nextFrame();
            }
            short[] decoded = Pcm.fromBytes(out.toByteArray(), ByteOrder.LITTLE_ENDIAN);
            short[] mono = Pcm.mono(decoded, channels);
            return Pcm.resample(mono, rate, targetRate);
        }
    }

    /**
     * OGG/Opus: страницы, внутри них пакеты с lacing-таблицей. Декодер берём
     * на целевой частоте — сам сводит 48 кГц к той, что нужна кодеку.
     */
    public static short[] ogg(byte[] bytes, int targetRate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int channels = 1;
        int preSkip = 0;
        OpusDecoder decoder = null;
        int at = 0;
        while (at + 27 <= bytes.length && isOggAt(bytes, at)) {
            int segments = bytes[at + 26] & 0xFF;
            int bodySize = 0;
            for (int i = 0; i < segments; i++) {
                bodySize += bytes[at + 27 + i] & 0xFF;
            }
            int bodyAt = at + 27 + segments;
            int bodyEnd = bodyAt + bodySize;

            if (decoder == null) {
                int head = indexOf(bytes, bodyAt, bodyEnd, "OpusHead");
                if (head >= 0) {
                    channels = bytes[head + 9] & 0xFF;
                    preSkip = (bytes[head + 10] & 0xFF) | ((bytes[head + 11] & 0xFF) << 8);
                }
                try {
                    decoder = new OpusDecoder(targetRate, channels);
                } catch (Exception e) {
                    throw new IOException("Декодер opus не поднялся", e);
                }
            }

            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            int pos = bodyAt;
            for (int i = 0; i < segments; i++) {
                int lacing = bytes[at + 27 + i] & 0xFF;
                packet.write(bytes, pos, lacing);
                pos += lacing;
                if (lacing == 255) continue; // пакет продолжается на следующем сегменте
                byte[] data = packet.toByteArray();
                packet.reset();
                short[] decoded = new short[targetRate * 20 / 1000];
                int count;
                try {
                    count = decoder.decode(data, 0, data.length, decoded, 0, decoded.length, false);
                } catch (Exception e) {
                    // заголовки потока (OpusHead, OpusTags) — не звук, их пропускаем
                    continue;
                }
                for (int s = 0; s < count; s++) {
                    out.write(decoded[s] & 0xFF);
                    out.write((decoded[s] >> 8) & 0xFF);
                }
            }
            at = bodyEnd;
        }

        if (decoder == null) {
            throw new IllegalStateException("OGG без заголовка OpusHead");
        }
        short[] samples = Pcm.fromBytes(out.toByteArray(), ByteOrder.LITTLE_ENDIAN);
        short[] mono = Pcm.mono(samples, channels);
        // разогрев декодера — первые отсчёты не звук, их выбрасываем
        int warm = Math.min(mono.length, preSkip * targetRate / 48_000);
        return warm == 0 ? mono : java.util.Arrays.copyOfRange(mono, warm, mono.length);
    }

    private static boolean isOggAt(byte[] bytes, int at) {
        return bytes[at] == 'O' && bytes[at + 1] == 'g' && bytes[at + 2] == 'g' && bytes[at + 3] == 'S';
    }

    private static boolean isOgg(byte[] bytes) {
        return bytes.length >= 4 && isOggAt(bytes, 0);
    }

    private static boolean isWav(byte[] bytes) {
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
    }

    /**
     * Начинается ли ответ с mp3. Заголовок кадра проверяется по битам: у
     * случайных отсчётов совпадение синхрослова возможно, а вот версия,
     * слой и индекс битрейта — уже нет.
     */
    private static boolean looksLikeMp3(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return true;
        }
        if (bytes.length < 4) {
            return false;
        }
        int sync = bytes[0] & 0xFF;
        int flags = bytes[1] & 0xFF;
        int version = (flags >> 3) & 0x03;      // 0 — зарезервировано
        int layer = (flags >> 1) & 0x03;        // 0 — зарезервировано
        int bitrate = (bytes[2] >> 4) & 0x0F;   // 0 и 15 — не бывает
        int rate = (bytes[2] >> 2) & 0x03;      // 3 — зарезервировано
        return sync == 0xFF && version != 0 && layer != 0 && bitrate != 0 && bitrate != 15 && rate != 3;
    }

    private static int indexOf(byte[] bytes, int from, int to, String needle) {
        outer:
        for (int at = from; at + needle.length() <= to; at++) {
            for (int i = 0; i < needle.length(); i++) {
                if (bytes[at + i] != (byte) needle.charAt(i)) {
                    continue outer;
                }
            }
            return at;
        }
        return -1;
    }
}

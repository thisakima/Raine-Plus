package ru.rainedev.raine.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Звук приходит в разных контейнерах: WAV от Fish Audio, mp3 и opus от
 * OpenAI-совместимых шлюзов, отсчёты — от тех, что pcm умеют.
 */
class SoundTest {

    private static byte[] wavOf(short[] interleaved, int rate, int channels) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + interleaved.length * 2);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(rate);
        header.putInt(rate * channels * 2);
        header.putShort((short) (channels * 2));
        header.putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(interleaved.length * 2);
        out.write(header.array());
        ByteBuffer body = ByteBuffer.allocate(interleaved.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : interleaved) {
            body.putShort(sample);
        }
        out.write(body.array());
        return out.toByteArray();
    }

    private static short[] tone(int rate, int hertz, double seconds) {
        short[] samples = new short[(int) (rate * seconds)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (Math.sin(2 * Math.PI * hertz * i / rate) * 8000);
        }
        return samples;
    }

    /** Частота на слух: считаем, сколько раз волна перешла через ноль. */
    private static double hertzOf(short[] samples, int rate) {
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if (samples[i - 1] < 0 && samples[i] >= 0) {
                crossings++;
            }
        }
        return crossings / (samples.length / (double) rate);
    }

    @Test
    void fishWavBecomesMonoAtTargetRate() throws IOException {
        short[] source = tone(44_100, 440, 1);

        short[] samples = Sound.wav(wavOf(source, 44_100, 1), 24_000);

        assertEquals(24_000, samples.length, 2, "длина должна соответствовать новой частоте");
        assertEquals(440, hertzOf(samples, 24_000), 5, "тон не должен уехать");
    }

    @Test
    void stereoChannelsAreAveraged() throws IOException {
        short[] stereo = {100, 300, -50, -150};

        short[] mono = Sound.wav(wavOf(stereo, 24_000, 2), 24_000);

        assertEquals(2, mono.length);
        assertEquals(200, mono[0]);
        assertEquals(-100, mono[1]);
    }

    @Test
    void loudnessSurvivesTheTrip() throws IOException {
        short[] source = tone(44_100, 440, 1);

        short[] samples = Sound.wav(wavOf(source, 44_100, 1), 24_000);

        int loudest = 0;
        for (short sample : samples) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        assertTrue(loudest > 7000 && loudest < 9000, "громкость уехала: " + loudest);
    }

    @Test
    void notAWavIsRejected() {
        assertThrows(IllegalStateException.class, () -> Sound.wav(new byte[]{1, 2, 3, 4}, 24_000));
    }

    /**
     * Fish отдаёт потоковый WAV: размера заранее не знает и пишет в заголовки
     * мусор. Разбирать всё равно надо — звук настоящий.
     */
    @Test
    void streamedWavWithBogusSizeIsAccepted() throws IOException {
        byte[] wav = wavOf(tone(44_100, 440, 1), 44_100, 1);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(4, 0xffffff24);
        header.putInt(40, 0xffffff00);

        short[] samples = Sound.wav(wav, 24_000);

        assertEquals(24_000, samples.length, 2);
        assertEquals(440, hertzOf(samples, 24_000), 5);
    }

    /** Пустые кадры MPEG-1 Layer III: тишина, но структура настоящая. */
    private static byte[] mp3Of(int frames) {
        byte[] data = new byte[frames * 417];
        for (int i = 0; i < frames; i++) {
            int at = i * 417;
            data[at] = (byte) 0xFF;      // синхронизация
            data[at + 1] = (byte) 0xFB;  // MPEG1 Layer III без CRC
            data[at + 2] = (byte) 0x90;  // 128 kbps, 44100
            data[at + 3] = 0x00;
        }
        return data;
    }

    @Test
    void mp3BecomesMonoAtTargetRate() throws IOException {
        short[] samples = Sound.mp3(mp3Of(10), 24_000);

        assertEquals(10 * 1152L * 24_000 / 44_100, samples.length, 5, "длина по частоте и числу кадров");
    }

    /** Круг: закодировали тон своим же кодеком и раскодировали ответ. */
    @Test
    void oggRoundTripKeepsTheTone() throws IOException {
        byte[] ogg = OggOpus.encode(tone(24_000, 440, 1), 24_000, 24_000);

        short[] samples = Sound.ogg(ogg, 24_000);

        assertEquals(24_000, samples.length, 200, "поток должен вытянуть почти секунду");
        assertEquals(440, hertzOf(samples, 24_000), 5, "тон не должен уехать");
    }

    @Test
    void formatIsDetectedFromTheBody() throws IOException {
        byte[] wav = wavOf(tone(24_000, 440, 1), 24_000, 1);
        byte[] ogg = OggOpus.encode(tone(24_000, 440, 1), 24_000, 24_000);
        short[] source = tone(24_000, 440, 1);
        byte[] pcm = new byte[source.length * 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(source);

        assertEquals(24_000, Sound.decode(wav, "audio/wav", 24_000).length, 2);
        assertEquals(24_000, Sound.decode(ogg, "audio/ogg", 24_000).length, 200);
        assertEquals(10 * 1152L * 24_000 / 44_100, Sound.decode(mp3Of(10), "audio/mpeg", 24_000).length, 5);
        assertEquals(source.length, Sound.decode(pcm, "audio/pcm", 24_000).length, "отсчёты читаются как есть");
    }
}

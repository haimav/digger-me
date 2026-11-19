/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package digger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 *
 * @author haima
 *
 * Android port: the JavaME version kept the high scores in an RMS RecordStore.
 * Here they are in a file in the application's private storage, holding exactly
 * what the record held (the bytes of scoresAsBytes()).
 */
public class ScoreStorage {

    private static String FILE_NAME = "digger.scores";

    private static File directory = null;

    /**
     * Android port: where the file is kept (Context.getFilesDir()). Set by
     * DiggerActivity before the game starts.
     */
    public static void setDirectory(File dir) {
        directory = dir;
    }

    private static byte[] scoresAsBytes(String names[], long scores[]) throws IOException {
        // names have 3 chars and there are 11 (each 2 bytes)
        // scores are 8 byte and there are 12
        int size = 6 * 11 + 8 * 12;
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(size);
        DataOutputStream out = new DataOutputStream(byteStream);
        for (int i = 0; i < 11; i++) {
            out.writeChar(names[i].charAt(0));
            out.writeChar(names[i].charAt(1));
            out.writeChar(names[i].charAt(2));
        }
        for (int i = 0; i < 12; i++) {
            out.writeLong(scores[i]);
        }

        return byteStream.toByteArray();
    }

    /**
     * Reads the scores from storage, returnning true if the exist and false if
     * not.
     *
     * @param names
     * @param scores
     * @return
     */
    public static boolean readFromStorage(String names[], long scores[]) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(readFile()));
            String[] readNames = new String[11];
            long[] readScores = new long[12];
            for (int i = 0; i < 11; i++) {
                char name[] = {'.', '.', '.'};
                name[0] = in.readChar();
                name[1] = in.readChar();
                name[2] = in.readChar();
                readNames[i] = new String(name);
            }
            for (int i = 0; i < 12; i++) {
                readScores[i] = in.readLong();
            }
            // Android port: copied only once all was read, and true is returned
            // (the JavaME version always returned false).
            System.arraycopy(readNames, 0, names, 0, 11);
            System.arraycopy(readScores, 0, scores, 0, 12);
            return true;
        } catch (Exception ex) {
        // Can't read then don't read...
        }
        return false;
    }

    /**
     * Writes updated results, overwriting old ones.
     *
     * @param names
     * @param scores
     */
    public static void writeToStorage(String names[], long scores[]) {
        try {
            byte[] data = scoresAsBytes(names, scores);

            // Write a new file and put it in place of the old one, so that a
            // crash in the middle never leaves half a table behind.
            File temp = new File(directory, FILE_NAME + ".new");
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(data);
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!temp.renameTo(new File(directory, FILE_NAME))) {
                temp.delete();
            }
        } catch (Exception ex) {
        // Can't write then don't write...
        }
    }

    /**
     * Creates empty result set on device, deleting old one if it exists.
     */
    public static void createInStorage(String names[], long scores[]) {
        writeToStorage(names, scores);
    }

    public static boolean scoreStorageExists() {
        return directory != null && new File(directory, FILE_NAME).exists();
    }

    private static byte[] readFile() throws IOException {
        File file = new File(directory, FILE_NAME);
        byte[] data = new byte[(int) file.length()];
        FileInputStream in = new FileInputStream(file);
        try {
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0)
                    throw new IOException("Unexpected end of " + file);
                read += n;
            }
        } finally {
            in.close();
        }
        return data;
    }
}

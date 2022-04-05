import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;
import support.CustomFileChooser;

import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.lang.reflect.InvocationTargetException;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice the audio samples are written to.
     */
    private AudioDevice device;

    private PlayerWindow window;

    private boolean repeat = false;
    private boolean shuffle = false;
    private boolean playerEnabled = false;
    private boolean playerPaused = true;
    private Song currentSong;
    private int currentFrame = 0;
    private int newFrame;
    private String[][] songArray;
    ReentrantLock lock = new ReentrantLock();
    private static int maxFrames;
    //private static Demo.DemoWindow demoWindow;

    public Player() {
        songArray = new String[0][6];

        //button events
        ActionListener buttonListenerPlayNow = e -> {
            start(window.getSelectedSong());

        };
        ActionListener buttonListenerRemove =  e -> removeFromQueue(window.getSelectedSong());
        ActionListener buttonListenerAddSong =  e -> {
            addToQueue();
        };
        ActionListener buttonListenerPlayPause =  e -> {
            playerEnabled = !playerEnabled;
            if (playerEnabled) {
                pause();
            }
        };
        ActionListener buttonListenerStop =  e -> stop();
        ActionListener buttonListenerNext =  e -> next();
        ActionListener buttonListenerPrevious =  e -> previous();
        ActionListener buttonListenerShuffle =  e -> {
            shuffle = !shuffle;
            if (shuffle) {
                pause();
            }
        };
        ActionListener buttonListenerRepeat =  e -> {
            repeat = !repeat;
            if (repeat) {
                pause();

            }
        };

        //mouse events
        MouseMotionListener scrubberListenerMotion = new MouseMotionListener() {
            @Override
            public void mouseDragged(MouseEvent e) {
                mouseDragged(e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {}
        };
        MouseListener scrubberListenerClick = new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent e) {}

            @Override
            public void mousePressed(MouseEvent e) {}

            @Override
            public void mouseReleased(MouseEvent e) {
                mouseReleased(e);
            }

            @Override
            public void mouseEntered(MouseEvent e) {}

            @Override
            public void mouseExited(MouseEvent e) {}
        };

        String windowTitle = "Spotify Moral";

        window = new PlayerWindow(
                windowTitle,
                songArray,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerRepeat,
                scrubberListenerClick,
                scrubberListenerMotion);
    }

    //<editor-fold desc="Essential">

    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        // TODO Is this thread safe?
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        // TODO Is this thread safe?
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     * @throws BitstreamException
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        // TODO Is this thread safe?
        if (newFrame > currentFrame) {
            int framesToSkip = newFrame - currentFrame;
            boolean condition = true;
            while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
        }
    }
    //</editor-fold>

    //<editor-fold desc="Queue Utilities">
    public void addToQueue() { // confirmar se precisa de parametro
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    Song songString = window.getNewSong();
                    String[][] newSongArray = new String[songArray.length + 1][6];

                    System.arraycopy(songArray, 0, newSongArray, 0, songArray.length);

                    newSongArray[songArray.length][0] = songString.getTitle();
                    newSongArray[songArray.length][1] = songString.getAlbum();
                    newSongArray[songArray.length][2] = songString.getArtist();
                    newSongArray[songArray.length][3] = songString.getYear();
                    newSongArray[songArray.length][4] = songString.getStrLength();
                    newSongArray[songArray.length][5] = songString.getFilePath();


                    songArray = newSongArray;
                    window.updateQueueList(newSongArray);

                } catch(IOException | BitstreamException | UnsupportedTagException |InvalidDataException xu){
                    System.out.println("deu merda");
                } finally {
                    lock.unlock();
                }
            }
        }).start();
    }

    public void removeFromQueue(String filePath) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    int aux_index = 0;
                    int aSize = songArray.length;
                    // int songID = Integer.parseInt(this.window.getSelectedSong());
                    String[][] newSongArray = new String[songArray.length - 1][6];

                    for (int i = 0; i < aSize - 1; i++) {
                        if (filePath.equals(songArray[aux_index][5])) {
                            aux_index++;
                        }
                        newSongArray[i] = songArray[aux_index];
                        aux_index++;
                    }
                    songArray = newSongArray;
                    window.updateQueueList(newSongArray);
                } finally {
                    lock.unlock();
                }
            }
        }).start();
    }

    //</editor-fold>

    //<editor-fold desc="Controls">
    public void start(String filePath) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                lock.lock();
                try {
                    //File file = fileChooser.getSelectedFile();
                    maxFrames = new Mp3File(filePath).getFrameCount();
                    device = FactoryRegistry.systemRegistry().createAudioDevice();
                    device.open(decoder = new Decoder());
                    bitstream = new Bitstream(new BufferedInputStream(new FileInputStream(filePath)));
//                        bitstream = new Bitstream(array[1].getBufferedInputStream());
                    //progressBar.setMaximum(maxFrames);
                } catch (JavaLayerException | InvalidDataException | UnsupportedTagException | IOException ex) {
                    ex.printStackTrace();
                }
                lock.unlock();
                if (device != null) {
                    try {
                        Header h;
                        int currentFrame = 0;
                        do {
                            h = bitstream.readFrame();
                            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                            device.write(output.getBuffer(), 0, output.getBufferLength());
                            bitstream.closeFrame();
                            //demoWindow.setProgress(currentFrame);
                            currentFrame++;
                        } while (h != null);
                    } catch (JavaLayerException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    };

    public void stop() {
    }

    public void Pause() {
        try{
            lock.lock();
            
        }
    }

    public void resume() {
    }

    public void next() {
    }

    public void previous() {
    }
    //</editor-fold>

    //<editor-fold desc="Getters and Setters">

    //</editor-fold>
}

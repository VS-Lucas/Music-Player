import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;
import support.CustomFileChooser;

import java.awt.event.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;


import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

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
    boolean toRemove = false;
    private Song currentSong;
    private int currentFrame = 0;
    private int newFrame;
    private String[][] songArray;
    ReentrantLock lock = new ReentrantLock();
    private final Condition playPauseCondition = lock.newCondition();
    private final Condition removeCondition = lock.newCondition();
    private static int maxFrames;
    boolean isPlaying = false;
    ArrayList <Song> newSongArray = new ArrayList();
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
            PlayPause();
        };
        ActionListener buttonListenerStop =  e -> stop();
        ActionListener buttonListenerNext =  e -> next();
        ActionListener buttonListenerPrevious =  e -> previous();
        ActionListener buttonListenerShuffle =  e -> {
            shuffle = !shuffle;
            if (shuffle) {
                PlayPause();
            }
        };
        ActionListener buttonListenerRepeat =  e -> {
            repeat = !repeat;
            if (repeat) {
                PlayPause();
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

    public String[][] queueToString(){
        String[][] auxArray = new String[newSongArray.size()][];
        for (int i = 0; i < newSongArray.size(); i++) {
            auxArray[i] = newSongArray.get(i).getDisplayInfo();
        }
        return auxArray;
    }

    //<editor-fold desc="Queue Utilities">
    public void addToQueue() {
        Thread add = new Thread(() -> {
            try {
                lock.lock();
                newSongArray.add(window.getNewSong());
                window.updateQueueList(queueToString());

            } catch(IOException | BitstreamException | UnsupportedTagException |InvalidDataException xu){
                System.out.println("deu merda");
            } finally {
                lock.unlock();
            }
        });add.start();
    }

    public void removeFromQueue(String filePath) { 
        Thread remove = new Thread((new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();

                    for (int i = 0; i < newSongArray.size(); i++) {
                        if (newSongArray.get(i).getFilePath().equals(filePath)) {
                            if(isPlaying){
                                toRemove = true;
                                window.resetMiniPlayer();
                            }
                            newSongArray.remove(i);
                        }
                    }
                    window.updateQueueList(queueToString());

                } finally {
                    lock.unlock();
                }
            };

        }));
        remove.start();

    };

    //</editor-fold>

    //<editor-fold desc="Controls">
    public void start(String filePath) {
        Thread start = new Thread(() -> {
            Song currentSong;
            lock.lock();
            try {
                for (int i = 0; i <newSongArray.size(); i++) {
                    if(newSongArray.get(i).getFilePath().equals(filePath)){
                        currentSong = newSongArray.get(i);
                        bitstream = new Bitstream(currentSong.getBufferedInputStream());
                        device = FactoryRegistry.systemRegistry().createAudioDevice();
                        device.open(decoder = new Decoder());
                        if (device != null) {
                            try {
                                isPlaying = true;
                                window.setEnabledPlayPauseButton(true);
                                window.updatePlayPauseButtonIcon(false);
                                //                    Header h;
                                // getRemove = false -> still playing
                                int currentFrame = 0;
                                lock.unlock();
                                while (playNextFrame()){
                                    window.setTime((int) (currentFrame* currentSong.getMsPerFrame()), (int) (currentSong.getNumFrames()*currentSong.getMsPerFrame()));
                                    if(!isPlaying){
                                        lock.lock();
                                        try {
                                            playPauseCondition.await();
                                        } finally {
                                            lock.unlock();
                                        }
                                    }
                                    if(getRemove()) {
                                        toRemove = false;
                                        break;
                                    }
                                    currentFrame++;
                                };
                            } catch (JavaLayerException e) {
                                e.printStackTrace();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            } catch (JavaLayerException | IOException ex) {
                ex.printStackTrace();
            }finally {
                window.resetMiniPlayer();
                if(lock.isLocked()) lock.unlock();
            }
        });start.start();
    };

    /// EXEMPLO
    public boolean getRemove(){
        lock.lock();
        try{
            return toRemove;
        }
        finally {
            lock.unlock();
        }
    }

    public void stop() {
    }

    public void PlayPause() {
        Thread pp = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    if(isPlaying){
                        //System.out.println("pausou");
                        isPlaying = false;
                        window.updatePlayPauseButtonIcon(true);

                    } else {
                        //System.out.println("recomeçou");
                        isPlaying = true;
                        playPauseCondition.signalAll();
                        window.updatePlayPauseButtonIcon(false);
                    }
                }finally {
                    lock.unlock();
                }
            }
        });pp.start();
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

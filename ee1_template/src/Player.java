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
    private int test;
    private Thread start;
    private int aux;
    private String[][] songArray;
    ReentrantLock lock = new ReentrantLock();
    private final Condition playPauseCondition = lock.newCondition();
    private final Condition removeCondition = lock.newCondition();
    private boolean flag = true;
    private boolean sec_flag = false;
    private String aux_remove;
    private boolean NextPrevious = false;
    private int updatedFrame;
    private boolean isPlaying = false;
    private boolean toStop = false;
    private boolean aux_test = true;
    private int counter = 0;
    private boolean nextSong = false;
    private boolean previousSong = false;
    ArrayList <Song> newSongArray = new ArrayList();
    //private static Demo.DemoWindow demoWindow;

    public Player() {
        songArray = new String[0][6];

        //button events
        ActionListener buttonListenerAddSong =  e -> {
            addToQueue();
        };
        ActionListener buttonListenerPlayNow = e -> {
            NextPrevious = false;
            start(window.getSelectedSong());
        };
        ActionListener buttonListenerRemove =  e -> {
            removeFromQueue(window.getSelectedSong());
        };
        ActionListener buttonListenerPlayPause =  e -> {
            if(isPlaying){
                pause();
            }else resume();
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
                if(isPlaying){
                    pause();
                }
                lock.lock();
                try {
                    updatedFrame = window.getScrubberValue() / (int) currentSong.getMsPerFrame();
                    window.setTime((int) (updatedFrame * currentSong.getMsPerFrame()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {}
        };
        MouseListener scrubberListenerClick = new MouseListener() {
            @Override
            public void mousePressed(MouseEvent e) {
                pause();
                lock.lock();
                try {
                    updatedFrame = window.getScrubberValue() / (int) currentSong.getMsPerFrame();
                    window.setTime((int) (updatedFrame * currentSong.getMsPerFrame()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));
                } finally {
                    lock.unlock();
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                Thread mR = new Thread(() -> {
                    lock.lock();
                    try {
                        if (currentFrame > updatedFrame) {
                            bitstream.close();
                            device = FactoryRegistry.systemRegistry().createAudioDevice();
                            device.open(decoder = new Decoder());
                            bitstream = new Bitstream(currentSong.getBufferedInputStream());
                            currentFrame = 0;
                            skipToFrame(updatedFrame);
                        } else {
                            skipToFrame(updatedFrame);
                        }
                        currentFrame = updatedFrame;
                        window.setTime((int) (currentFrame * currentSong.getMsPerFrame()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));
                        resume();
                    } catch (FileNotFoundException | JavaLayerException f) {
                        f.printStackTrace();
                    } finally {
                        lock.unlock();
                    }
                });mR.start();
            }

            @Override
            public void mouseClicked(MouseEvent e) {}

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
        lock.lock();
        try{
            if (device != null) {
                Header h = bitstream.readFrame();
                if (h == null) return false;

                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                device.write(output.getBuffer(), 0, output.getBufferLength());
                bitstream.closeFrame();
            }
        }finally {
            lock.unlock();
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
        lock.lock();
        try{
            if (newFrame > currentFrame) {
                int framesToSkip = newFrame - currentFrame;
                boolean condition = true;
                while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
            }
        }finally {
            lock.unlock();
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
                Song newSong = window.getNewSong();
                if(newSong != null){
                    newSongArray.add(newSong);
                    window.updateQueueList(queueToString());
                }
            } catch(IOException | BitstreamException | UnsupportedTagException |InvalidDataException xu){
                System.out.println("deu merda");
            } finally {
                lock.unlock();
            }
        });add.start();
    }

    public void removeFromQueue(String filePath) {
        Thread remove = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    for (int i = 0; i < newSongArray.size(); i++) {
                        if (newSongArray.get(i).getFilePath().equals(filePath)) {
                            if(currentSong.getFilePath().equals(filePath)){
                                flag = false;
                                if(aux < newSongArray.size()-1){
                                    sec_flag = true;
                                    flag = true;
                                    nextSong = true;
                                }
                            }
                            newSongArray.remove(i);
                        }
                    }
                    window.updateQueueList(queueToString());
                }
                finally {
                    lock.unlock();
                }
            };
        });
        remove.start();
    };

    //</editor-fold>

    //<editor-fold desc="Controls">
    public void start(String filePath) {
        lock.lock();
        try{
            if(start != null && start.isAlive()){
                start.interrupt();
            }
        }finally {
            lock.unlock();
        }

        start = new Thread(() -> {
            lock.lock();
            try {
                if(counter != 0){
                    bitstream.close();
                }
                flag = true;
                for (int i = 0; i < newSongArray.size(); i++) {
                    if (newSongArray.get(i).getFilePath().equals(filePath) && !NextPrevious) {
                        currentSong = newSongArray.get(i);
                        aux = i;
                        bitstream = new Bitstream(currentSong.getBufferedInputStream());
                        device = FactoryRegistry.systemRegistry().createAudioDevice();
                        device.open(decoder = new Decoder());
                        counter++;

                    }else if(NextPrevious){
                        if (nextSong) {

                            nextSong = false;
                            aux = aux + 1;
                            if(sec_flag){
                                aux--;
                            }
                            sec_flag = false;
                            currentSong = newSongArray.get(aux);
                            bitstream = new Bitstream(currentSong.getBufferedInputStream());
                            device = FactoryRegistry.systemRegistry().createAudioDevice();
                            device.open(decoder = new Decoder());
                        } else if (previousSong) {
                            previousSong = false;
                            aux = aux - 1;
                            currentSong = newSongArray.get(aux);
                            bitstream = new Bitstream(currentSong.getBufferedInputStream());
                            device = FactoryRegistry.systemRegistry().createAudioDevice();
                            device.open(decoder = new Decoder());
                        }
                        NextPrevious = false;
                        break;
                    }
                }
                if (device != null) {
                    try {
                        currentFrame = 0;
                        isPlaying = true;
                        window.setEnabledPlayPauseButton(true);
                        window.updatePlayPauseButtonIcon(false);
                        lock.unlock();
                        while (playNextFrame()){
                            //lock.lock();
                            try{
                                if(!isPlaying){
                                    lock.lock();
                                    try {
                                        device.flush();
                                        playPauseCondition.await();
                                    } finally {
                                        lock.unlock();
                                    }
                                }
                                window.setEnabledScrubber(true);
                                window.setEnabledStopButton(true);
                                window.setTime((int) (currentFrame*currentSong.getMsPerFrame()), (int) (currentSong.getNumFrames()*currentSong.getMsPerFrame()));

                                if(aux != newSongArray.size()-1) {
                                    window.setEnabledNextButton(true);
                                }
                                if(aux != 0){
                                    window.setEnabledPreviousButton(true);
                                }

                                if(!flag){
                                    break;
                                }

                                if(toStop){
                                    toStop = false;
                                    break;
                                }

                                if(nextSong){
                                    NextPrevious = true;
                                    break;
                                }
                                if(previousSong){
                                    NextPrevious = true;
                                    break;
                                }
                                currentFrame++;
                            }finally {
                                //lock.unlock();
                            }
                        };
                    } catch (JavaLayerException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if(flag){
                    start(filePath);
                }

            } catch (JavaLayerException | IOException ex) {
                ex.printStackTrace();
            }finally {
                window.resetMiniPlayer();

                if(lock.isHeldByCurrentThread() && lock.isLocked()) {
                    lock.unlock();
                }
            }
        });
        test++;
        start.setName(String.valueOf(test));
        start.start();
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
        lock.lock();
        try{
            flag = false;
            toStop = true;
        }finally {
            lock.unlock();
        }

    }

   public void pause() {
        try {
            lock.lock();
            isPlaying = false;
            window.updatePlayPauseButtonIcon(true);
        }finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try{
            isPlaying = true;
            playPauseCondition.signalAll();
            window.updatePlayPauseButtonIcon(false);
        }finally {
            lock.unlock();
        }
    }

    public void next() {
        nextSong = true;
    }

    public void previous() {
        previousSong = true;
    }
    //</editor-fold>

    //<editor-fold desc="Getters and Setters">

    //</editor-fold>
}

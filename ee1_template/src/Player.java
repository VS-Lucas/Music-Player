import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.*;

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
    private Song currentSong;
    private int currentFrame = 0;
    private int test;
    private Thread start;
    private Thread run;
    private int currentSongIndex;
    private String[][] songArray;
    ReentrantLock lock = new ReentrantLock();
    private final Condition playPauseCondition = lock.newCondition();
    private String aux_remove;
    private int updatedFrame;
    private boolean isPlaying = false;
    private boolean toStop = false;
    private boolean isRepeat = false;
    private boolean isShuffle = false;
    private boolean isPlayerEnabled = false;
    ArrayList <Song> songArrayList = new ArrayList();
    ArrayList <Song> copySongArrayList = new ArrayList();

    public Player() {
        songArray = new String[0][6];

        //button events
        ActionListener buttonListenerAddSong =  e -> {
            addToQueue();
        };
        ActionListener buttonListenerPlayNow = e -> {
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
            shuffle();
        };
        ActionListener buttonListenerRepeat =  e -> {
            repeat();
        };

        //Mouse events
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

    public String[][] queueToString(){ //It returns a string array to update the queue
        String[][] auxArray = new String[songArrayList.size()][];
        for (int i = 0; i < songArrayList.size(); i++) {
            auxArray[i] = songArrayList.get(i).getDisplayInfo();
        }
        return auxArray;
    }

    public void addToQueue() { //It adds the new song in the playlist
        Thread add = new Thread(() -> {
            Song newSong = null;
            try {
                newSong = window.getNewSong();
            } catch (IOException | BitstreamException | UnsupportedTagException | InvalidDataException e) {
                e.printStackTrace();
            }
            try {
                lock.lock();
                if(newSong != null){
                    copySongArrayList.add(newSong); //To save the current list in case shuffle is called
                    songArrayList.add(newSong);
                    window.updateQueueList(queueToString());
                }
            } finally {
                lock.unlock();
            }
        });add.start();
    }

    public void removeFromQueue(String filePath) { //It removes the selected song from the playlist
        Thread remove = new Thread(() -> {
            boolean toNext = false;
            try {
                lock.lock();
                for (int i = 0; i < songArrayList.size(); i++) { //Interate along the array to find the song selected
                    if (songArrayList.get(i).getFilePath().equals(filePath)) {
                        songArrayList.remove(i);
                    }
                }
                if(isShuffle){ //Checks the condition to remove from the CopyArray
                    for(int i = 0; i < copySongArrayList.size(); i++) {
                        if(copySongArrayList.get(i).getFilePath().equals(filePath)){
                            copySongArrayList.remove(i);
                            break;
                        }
                    }
                }
                window.updateQueueList(queueToString());
                if(currentSong.getFilePath().equals(filePath)){
                    toNext = true;
                }
            }
            finally {
                lock.unlock();
            }
            if (toNext) { // It plays the next song after the removed one
                stop();
                lock.lock();
                try {
                    toStop = false;
                } finally {
                    lock.unlock();
                }
                run(currentSongIndex);
            }
        });
        remove.start();
    };

    public void start(String filePath) { // It finds the index of the selected song and calls run method to play the song
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
                for (int i = 0; i < songArrayList.size(); i++) {
                    if (songArrayList.get(i).getFilePath().equals(filePath)) {
                        currentSongIndex = i;
                        toStop = false;
                        run(currentSongIndex); // Passing the index to play the song
                        break;
                    }
                }
            } finally {
                if(lock.isHeldByCurrentThread() && lock.isLocked()) {
                    lock.unlock();
                }
            }
        });
        start.start();
    }

    private void run(int index) { //It plays the song in the index provided by start()
        run = new Thread(() -> {
            lock.lock();
            currentSongIndex = index;
            currentSong = songArrayList.get(index);
            try {
                isPlayerEnabled = true;
                bitstream = new Bitstream(currentSong.getBufferedInputStream());
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                if (currentSongIndex != songArrayList.size() - 1 || isRepeat) {
                    window.setEnabledNextButton(true);
                }
                if (currentSongIndex != 0) {
                    window.setEnabledPreviousButton(true);
                }
                window.updatePlayingSongInfo(currentSong.getTitle(), currentSong.getAlbum(), currentSong.getArtist());
                window.setEnabledPlayPauseButton(true);
                window.updatePlayPauseButtonIcon(false);
                window.setEnabledScrubber(true);
                window.setEnabledStopButton(true);
                currentFrame = 0;
                isPlaying = true;
            } catch (FileNotFoundException | JavaLayerException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                while (playNextFrame()) { //While there's frames left
                    lock.lock();
                    try {
                        if (!isPlaying) {
                            device.flush();
                            playPauseCondition.await();
                        }
                        window.setTime((int) (currentFrame * currentSong.getMsPerFrame()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));

                        if (toStop) {
                            break;
                        }
                        currentFrame++;
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (JavaLayerException | InterruptedException e) {
                e.printStackTrace();
            }
            window.resetMiniPlayer();
            if (!getStop()) {
                next();
            }
        });
        run.start();
    }

    public void stop() { //Stop the current song of playing
        lock.lock();
        try{
            toStop = true;
            isPlayerEnabled = false;
        }finally {
            lock.unlock();
        }

        try {
            start.join();
            run.join();
        } catch (InterruptedException ignored) {}
    }

   public void pause() { //Set pause settings
        try {
            lock.lock();
            isPlaying = false; //Not playing the song
            window.updatePlayPauseButtonIcon(true);
        }finally {
            lock.unlock();
        }
    }

    public void resume() { //Returns to play the song from the moment it was paused
        lock.lock();
        try{
            isPlaying = true; //Plays the song again
            playPauseCondition.signalAll();
            window.updatePlayPauseButtonIcon(false);
        }finally {
            lock.unlock();
        }
    }

    public void next(){ //Enable the next song to play
        lock.lock();
        try{
            toStop = false;
            if(isRepeat){ //If the repeat button is enabled
                if(currentSongIndex == songArrayList.size()-1){ //Checks if the current song is the last one
                    run(0); //It plays the first song of the playlist
                }
                else{
                    run(currentSongIndex + 1); //Otherwise, it plays the next one in the array
                }
            }
            else{  //If the repeat button is disabled
                if(currentSongIndex != songArrayList.size()-1){ //Checks if the current song isn't the last one
                    run(currentSongIndex + 1); //It plays the next song
                }
            }
        }finally {
            lock.unlock();
        }
    }

    public void previous() { //Enable to play the previous song
        lock.lock();
        try {
            toStop = false;
            if(currentSongIndex != 0){ //Checks if the current song isn't the first one
                run(currentSongIndex - 1); //Plays the song
            }
        }finally {
            lock.unlock();
        }
    }

    public void repeat(){ //Enable the repeat method
        lock.lock();
        try {
            isRepeat = !isRepeat;
        }finally {
            lock.unlock();
        }
    }

    public void shuffle(){ //Enable the shuffle method
        lock.lock();
        try {
            isShuffle = !isShuffle;
            if(isShuffle){ //Checks if shuffle is enabled
                copySongArrayList.clear(); //Clear the last copyArray to update
                copySongArrayList.addAll(songArrayList); //Creates a new array that store the initial queue

                if(isPlayerEnabled){
                    songArrayList.remove(currentSongIndex); //Removes the current song
                    Collections.shuffle(songArrayList); //Shuffle method applied
                    songArrayList.add(0, copySongArrayList.get(currentSongIndex)); //Adds the current song in the first index of the copySongArrayList
                }
                else{
                    Collections.shuffle(songArrayList); //Shuffle method
                }
                currentSongIndex = 0;
            }
            else{
                String songFilePath = currentSong.getFilePath();
                for (int i = 0; i < copySongArrayList.size(); i++) {
                    if(copySongArrayList.get(i).getFilePath().equals(songFilePath)){ //It finds the index of the current song in the copySongArrayList
                        currentSongIndex = i;
                        break;
                    }
                }
                songArrayList.clear();
                songArrayList.addAll(copySongArrayList);
            }
            window.updateQueueList(queueToString()); //Update the queue. If shuffle is enabled, update a shuffled queue. If not, update the initial queue
        }finally {
            lock.unlock();
        }
    }

    public boolean getStop(){
        lock.lock();
        try {
            return toStop;
        } finally {
            lock.unlock();
        }
    }
}

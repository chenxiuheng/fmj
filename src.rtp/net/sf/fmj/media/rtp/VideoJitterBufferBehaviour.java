package net.sf.fmj.media.rtp;

import java.util.concurrent.locks.*;

import javax.media.*;
import javax.media.protocol.*;

import net.sf.fmj.media.rtp.util.*;

/**
 * Jitter buffer for video.
 */
public class VideoJitterBufferBehaviour implements JitterBufferBehaviour
{
    private int minPackets = ConfigUtils.getIntConfig("video.minPackets", 4);
    private int maxPackets = ConfigUtils.getIntConfig("video.maxPackets", 128);

    private final Lock            videoLock          = new ReentrantLock();
    private final Condition       videoCondition     = videoLock.newCondition();
    private volatile boolean      videoDataAvailable = false;
    private JitterBuffer          q;
    private BufferTransferHandler handler;
    private RTPSourceStream       stream;
    private JitterBufferStats     stats;
    private RTPMediaThread        videoThread;
    private volatile boolean      running;

    /**
     * cTor
     *
     * @param q      The actual jitter to control the behaviour of.
     * @param stream The stream we're interacting with
     * @param stats  Stats to update
     */
    public VideoJitterBufferBehaviour(JitterBuffer q, RTPSourceStream stream, JitterBufferStats stats)
    {
        q.maxCapacity = maxPackets;
        this.q = q;
        this.stream = stream;
        this.stats = stats;
    }

    /**
     * Called each time there is a packet available.
     */
    private void transferVideoData()
    {
        if (q.getCurrentSize() > minPackets && handler != null)
        {
                handler.transferData(stream);
        }
    }

    @Override
    public void preAdd()
    {
            videoLock.lock();
            try
            {
                videoDataAvailable = true;
                videoCondition.signalAll();
            }
            finally
            {
                videoLock.unlock();
            }
    }


    @Override
    public void handleFull()
    {
            while(q.getCurrentSize() > minPackets)
            {
                stats.incrementDiscardedFull();
                q.dropOldest();
            }
    }

    @Override
    public void read(Buffer buffer)
    {
        Buffer bufferToCopyFrom = q.get();
        buffer.copy(bufferToCopyFrom);
    }

    @Override
    public void start()
    {
        running = true;
        if (videoThread == null)
        {
            videoThread = new RTPMediaThread("RTPStream")
            {
                @Override
                public void run()
                {
                    while (running)
                    {
                        videoLock.lock();

                        try
                        {
                            while (!videoDataAvailable)
                            {
                                videoCondition.awaitUninterruptibly();
                            }
                        }
                        finally
                        {
                            videoLock.unlock();
                        }

                        transferVideoData();
                    }
                }
            };
            videoThread.useVideoPriority();
            videoThread.start();
        }
    }

    @Override
    public void stop()
    {
        // Don't just stop the thread as that is depracated. Instead, signal
        // the thread (which is stuck in a loop) that it should end the loop
        // and come to a natural end.
        running = false;
    }

    @Override
    public void setTransferHandler(BufferTransferHandler handler)
    {
        this.handler = handler;
    }
}

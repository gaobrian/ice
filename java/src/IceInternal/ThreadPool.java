// **********************************************************************
//
// Copyright (c) 2003
// ZeroC, Inc.
// Billerica, MA, USA
//
// All Rights Reserved.
//
// Ice is free software; you can redistribute it and/or modify it under
// the terms of the GNU General Public License version 2 as published by
// the Free Software Foundation.
//
// **********************************************************************

package IceInternal;

public final class ThreadPool
{
    private final static boolean TRACE_REGISTRATION = false;
    private final static boolean TRACE_INTERRUPT = false;
    private final static boolean TRACE_SHUTDOWN = false;
    private final static boolean TRACE_SELECT = false;
    private final static boolean TRACE_EXCEPTION = false;
    private final static boolean TRACE_THREAD = false;
    private final static boolean TRACE_STACK_TRACE = false;

    public
    ThreadPool(Instance instance, String prefix, int timeout)
    {
        _instance = instance;
        _destroyed = false;
        _prefix = prefix;
        _timeout = timeout;
	_threadIndex = 0;
	_running = 0;
	_inUse = 0;
	_load = 0;
	_promote = true;
	_warnUdp = _instance.properties().getPropertyAsInt("Ice.Warn.Datagrams") > 0;

	String programName = _instance.properties().getProperty("Ice.ProgramName");
        if(programName.length() > 0)
        {
            _programNamePrefix = programName + "-";
        }
	else
	{
	    _programNamePrefix = "";
	}

        Network.SocketPair pair = Network.createPipe();
        _fdIntrRead = (java.nio.channels.ReadableByteChannel)pair.source;
        _fdIntrWrite = pair.sink;

        try
        {
            _selector = java.nio.channels.Selector.open();
            pair.source.configureBlocking(false);
            _fdIntrReadKey = pair.source.register(_selector, java.nio.channels.SelectionKey.OP_READ);
        }
        catch(java.io.IOException ex)
        {
            Ice.SyscallException sys = new Ice.SyscallException();
            sys.initCause(ex);
            throw sys;
        }

        //
        // The Selector holds a Set representing the selected keys. The
        // Set reference doesn't change, so we obtain it once here.
        //
        _keys = _selector.selectedKeys();

	int size = _instance.properties().getPropertyAsIntWithDefault(_prefix + ".Size", 5);
	if(size < 1)
	{
	    size = 1;
	}
	_size = size;
	
	int sizeMax = _instance.properties().getPropertyAsIntWithDefault(_prefix + ".SizeMax", _size * 10);
	if(sizeMax < _size)
	{
	    sizeMax = _size;
	}
	_sizeMax = sizeMax;

	int sizeWarn = _instance.properties().getPropertyAsIntWithDefault(_prefix + ".SizeWarn", _sizeMax * 80 / 100);
	_sizeWarn = sizeWarn;

        _messageSizeMax = _instance.messageSizeMax();

	try
        {
            _threads = new java.util.ArrayList();
            for(int i = 0; i < _size; i++)
            {
		EventHandlerThread thread = new EventHandlerThread(_programNamePrefix + _prefix + "-" +
								   _threadIndex++);
                _threads.add(thread);
		thread.start();
		++_running;
            }
        }
        catch(RuntimeException ex)
        {
	    java.io.StringWriter sw = new java.io.StringWriter();
	    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
	    ex.printStackTrace(pw);
	    pw.flush();
	    String s = "cannot create thread for `" + _prefix + "':\n" + sw.toString();
	    _instance.logger().error(s);

            destroy();
	    joinWithAllThreads();
            throw ex;
        }
    }

    protected void
    finalize()
        throws Throwable
    {
        assert(_destroyed);

        if(_selector != null)
        {
            try
            {
                _selector.close();
            }
            catch(java.io.IOException ex)
            {
            }
        }
        if(_fdIntrWrite != null)
        {
            try
            {
                _fdIntrWrite.close();
            }
            catch(java.io.IOException ex)
            {
            }
        }
        if(_fdIntrRead != null)
        {
            try
            {
                _fdIntrRead.close();
            }
            catch(java.io.IOException ex)
            {
            }
        }

        super.finalize();
    }

    public synchronized void
    destroy()
    {
        if(TRACE_SHUTDOWN)
        {
            trace("destroy");
        }

        assert(!_destroyed);
	assert(_handlerMap.isEmpty());
	assert(_changes.isEmpty());
        _destroyed = true;
        setInterrupt(0);
    }

    public synchronized void
    _register(java.nio.channels.SelectableChannel fd, EventHandler handler)
    {
        if(TRACE_REGISTRATION)
        {
            trace("adding handler of type " + handler.getClass().getName() + " for channel " + fd);
        }
	assert(!_destroyed);
        _changes.add(new FdHandlerPair(fd, handler));
        setInterrupt(0);
    }

    public synchronized void
    unregister(java.nio.channels.SelectableChannel fd)
    {
        if(TRACE_REGISTRATION)
        {
            if(TRACE_STACK_TRACE)
            {
                java.io.StringWriter sw = new java.io.StringWriter();
                try
                {
                    throw new RuntimeException();
                }
                catch(RuntimeException ex)
                {
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    ex.printStackTrace(pw);
                    pw.flush();
                }
                trace("removing handler for channel " + fd + "\n" + sw.toString());
            }
            else
            {
                trace("removing handler for channel " + fd);
            }
        }

	assert(!_destroyed);
        _changes.add(new FdHandlerPair(fd, null));
        setInterrupt(0);
    }

    public void
    promoteFollower()
    {
        if(_sizeMax > 1)
        {
	    synchronized(this)
	    {
		assert(!_promote);
		_promote = true;
		notify();
		    
		if(!_destroyed)
		{
		    assert(_inUse >= 0);
		    ++_inUse;
		    
		    if(_inUse == _sizeWarn)
		    {
			String s = "thread pool `" + _prefix + "' is running low on threads\n"
			    + "Size=" + _size + ", " + "SizeMax=" + _sizeMax + ", " + "SizeWarn=" + _sizeWarn;
			_instance.logger().warning(s);
		    }
		    
		    assert(_inUse <= _running);
		    if(_inUse < _sizeMax && _inUse == _running)
		    {
			try
			{
			    EventHandlerThread thread = new EventHandlerThread(_programNamePrefix + _prefix + "-" +
									       _threadIndex++);
			    _threads.add(thread);
			    thread.start();
			    ++_running;
			}
			catch(RuntimeException ex)
			{
			    java.io.StringWriter sw = new java.io.StringWriter();
			    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
			    ex.printStackTrace(pw);
			    pw.flush();
			    String s = "cannot create thread for `" + _prefix + "':\n" + sw.toString();
			    _instance.logger().error(s);
			}
		    }
		}
	    }
        }
    }

    public void
    initiateShutdown()
    {
        if(TRACE_SHUTDOWN)
        {
            trace("initiate server shutdown");
        }

        setInterrupt(1);
    }

    public void
    joinWithAllThreads()
    {
	//
	// _threads is immutable after destroy() has been called,
	// therefore no synchronization is needed. (Synchronization
	// wouldn't be possible here anyway, because otherwise the
	// other threads would never terminate.)
	//
	assert(_destroyed);
	java.util.Iterator i = _threads.iterator();
	while(i.hasNext())
	{
	    EventHandlerThread thread = (EventHandlerThread)i.next();
	    
            while(true)
            {
                try
                {
                    thread.join();
                    break;
                }
                catch(InterruptedException ex)
                {
                }
            }
        }
    }
    
    private boolean
    clearInterrupt()
    {
        if(TRACE_INTERRUPT)
        {
            trace("clearInterrupt");
            if(TRACE_STACK_TRACE)
            {
                try
                {
                    throw new RuntimeException();
                }
                catch(RuntimeException ex)
                {
                    ex.printStackTrace();
                }
            }
        }

        byte b = 0;

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1);
        try
        {
            while(true)
            {
                buf.rewind();
                if(_fdIntrRead.read(buf) != 1)
                {
                    break;
                }

                if(TRACE_INTERRUPT)
                {
                    trace("clearInterrupt got byte " + (int)buf.get(0));
                }

                b = buf.get(0);
                break;
            }
        }
        catch(java.io.IOException ex)
        {
            Ice.SocketException se = new Ice.SocketException();
            se.initCause(ex);
            throw se;
        }

        return b == (byte)1; // Return true if shutdown has been initiated.
    }

    private void
    setInterrupt(int b)
    {
        if(TRACE_INTERRUPT)
        {
            trace("setInterrupt(" + b + ")");
            if(TRACE_STACK_TRACE)
            {
                try
                {
                    throw new RuntimeException();
                }
                catch(RuntimeException ex)
                {
                    ex.printStackTrace();
                }
            }
        }

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1);
        buf.put(0, (byte)b);
        while(buf.hasRemaining())
        {
            try
            {
                _fdIntrWrite.write(buf);
            }
            catch(java.io.IOException ex)
            {
                Ice.SocketException se = new Ice.SocketException();
                se.initCause(ex);
                throw se;
            }
        }
    }

    //
    // Each thread supplies a BasicStream, to avoid creating excessive
    // garbage (Java only).
    //
    private boolean
    run(BasicStream stream)
    {
	if(_sizeMax > 1)
	{
	    synchronized(this)
	    {
		while(!_promote)
		{
		    try
		    {
			wait();
		    }
		    catch(InterruptedException ex)
		    {
		    }
		}
		
		_promote = false;
	    }
	    
	    if(TRACE_THREAD)
	    {
		trace("thread " + Thread.currentThread() + " has the lock");
	    }
	}

        while(true)
        {
	    if(TRACE_REGISTRATION)
	    {
		java.util.Set keys = _selector.keys();
		trace("selecting on " + keys.size() + " channels:");
		java.util.Iterator i = keys.iterator();
		while(i.hasNext())
		{
		    java.nio.channels.SelectionKey key = (java.nio.channels.SelectionKey)i.next();
		    trace("  " + key.channel());
		}
	    }
	    
	    select();
	    if(_keys.size() == 0) // We initiate a shutdown if there is a thread pool timeout.
	    {
		if(TRACE_SELECT)
		{
		    trace("timeout");
		}
		
		assert(_timeout > 0);
		_timeout = 0;
		initiateShutdown();
		continue;
	    }
	    
	    EventHandler handler = null;
	    boolean finished = false;
	    boolean shutdown = false;
	    
	    synchronized(this)
	    {
		if(_keys.contains(_fdIntrReadKey) && _fdIntrReadKey.isReadable())
		{
		    if(TRACE_SELECT || TRACE_INTERRUPT)
		    {
			trace("detected interrupt");
		    }
		    
		    //
		    // There are three possibilities for an interrupt:
		    //
		    // - The thread pool has been destroyed.
		    //
		    // - Server shutdown has been initiated.
		    //
		    // - An event handler was registered or unregistered.
		    //
		    
		    //
		    // Thread pool destroyed?
		    //
		    if(_destroyed)
		    {
			if(TRACE_SHUTDOWN)
			{
			    trace("destroyed, thread id = " + Thread.currentThread());
			}

			//
			// Don't clear the interrupt fd if destroyed,
			// so that the other threads exit as well.
			//
			return true;
		    }

		    //
		    // Remove the interrupt channel from the selected key set.
		    //
		    _keys.remove(_fdIntrReadKey);

		    shutdown = clearInterrupt();

		    if(!shutdown)
		    {
			//
			// An event handler must have been registered
			// or unregistered.
			//
			assert(!_changes.isEmpty());
			FdHandlerPair change = (FdHandlerPair)_changes.removeFirst();
			    
			if(change.handler != null) // Addition if handler is set.
			{
			    int op;
			    if((change.fd.validOps() & java.nio.channels.SelectionKey.OP_READ) > 0)
			    {
				op = java.nio.channels.SelectionKey.OP_READ;
			    }
			    else
			    {
				op = java.nio.channels.SelectionKey.OP_ACCEPT;
			    }
				
			    java.nio.channels.SelectionKey key = null;
			    try
			    {
				key = change.fd.register(_selector, op, change.handler);
			    }
			    catch(java.nio.channels.ClosedChannelException ex)
			    {
				assert(false);
			    }
			    _handlerMap.put(change.fd, new HandlerKeyPair(change.handler, key));
				
			    if(TRACE_REGISTRATION)
			    {
				trace("added handler (" + change.handler.getClass().getName() + ") for fd " +
				      change.fd);
			    }
				
			    continue;
			}
			else // Removal if handler is not set.
			{
			    HandlerKeyPair pair = (HandlerKeyPair)_handlerMap.remove(change.fd);
			    assert(pair != null);
			    handler = pair.handler;
			    finished = true;
			    pair.key.cancel();
				
			    if(TRACE_REGISTRATION)
			    {
				trace("removed handler (" + handler.getClass().getName() + ") for fd " +
				      change.fd);
			    }
				
			    // Don't continue; we have to call
			    // finished() on the event handler below,
			    // outside the thread synchronization.
			}
		    }
		}
		else
		{
		    java.nio.channels.SelectionKey key = null;
		    java.util.Iterator iter = _keys.iterator();
		    while(iter.hasNext())
		    {
			//
			// Ignore selection keys that have been cancelled
			//
			java.nio.channels.SelectionKey k = (java.nio.channels.SelectionKey)iter.next();
			iter.remove();
			if(k.isValid() && key != _fdIntrReadKey)
			{
			    if(TRACE_SELECT)
			    {
				trace("found a key: " + keyToString(k));
			    }

			    key = k;
			    break;
			}
		    }

		    if(key == null)
		    {
			if(TRACE_SELECT)
			{
			    trace("didn't find a valid key");
			}

			continue;
		    }

		    handler = (EventHandler)key.attachment();
		}
	    }

	    assert(handler != null || shutdown);

	    if(shutdown) // Shutdown has been initiated.
	    {
		if(TRACE_SHUTDOWN)
		{
		    trace("shutdown detected");
		}

		ObjectAdapterFactory factory;
		try
		{
		    factory = _instance.objectAdapterFactory();
		}
		catch(Ice.CommunicatorDestroyedException e)
		{
		    continue;
		}

		promoteFollower();
		factory.shutdown();
	    }
	    else
	    {
		if(finished)
		{
		    //
		    // Notify a handler about it's removal from
		    // the thread pool.
		    //
		    try
		    {
			handler.finished(this);
		    }
		    catch(Ice.LocalException ex)
		    {
			java.io.StringWriter sw = new java.io.StringWriter();
			java.io.PrintWriter pw = new java.io.PrintWriter(sw);
			ex.printStackTrace(pw);
			pw.flush();
			String s = "exception in `" + _prefix + "' while calling finished():\n" +
			    sw.toString() + "\n" + handler.toString();
			_instance.logger().error(s);
		    }
		}
		else
		{
		    //
		    // If the handler is "readable", try to read a
		    // message.
		    //
		    try
		    {
			if(handler.readable())
			{
			    try
			    {
				read(handler);
			    }
			    catch(Ice.TimeoutException ex) // Expected.
			    {
				continue;
			    }
			    catch(Ice.DatagramLimitException ex) // Expected.
			    {
			        continue;
			    }
			    catch(Ice.LocalException ex)
			    {
				if(TRACE_EXCEPTION)
				{
				    trace("informing handler (" + handler.getClass().getName() +
					  ") about exception " + ex);
				    ex.printStackTrace();
				}
				    
				handler.exception(ex);
				continue;
			    }
				
			    stream.swap(handler._stream);
			    assert(stream.pos() == stream.size());
			}
			    
			handler.message(stream, this);
		    }
		    finally
		    {
			stream.reset();
		    }
		}
	    }

	    if(_sizeMax > 1)
	    {
		synchronized(this)
		{
		    if(!_destroyed)
		    {
			//
			// First we reap threads that have been
			// destroyed before.
			//
			int sz = _threads.size();
			assert(_running <= sz);
			if(_running < sz)
			{
			    java.util.Iterator i = _threads.iterator();
			    while(i.hasNext())
			    {
				EventHandlerThread thread = (EventHandlerThread)i.next();

				if(!thread.isAlive())
				{
				    try
				    {
					thread.join();
					i.remove();
				    }
				    catch(InterruptedException ex)
				    {
				    }
				}
			    }
			}
			
			//
			// Now we check if this thread can be destroyed, based
			// on a load factor.
			//
			final double loadFactor = 0.05; // TODO: Configurable?
			final double oneMinusLoadFactor = 1 - loadFactor;
			_load = _load * oneMinusLoadFactor + _inUse * loadFactor;
			
			if(_running > _size)
			{
			    int load = (int)(_load + 1);
			    if(load < _running)
			    {
				assert(_inUse > 0);
				--_inUse;
				
				assert(_running > 0);
				--_running;
				
				return false;
			    }
			}
			
			assert(_inUse > 0);
			--_inUse;
		    }

		    while(!_promote)
		    {
			try
			{
			    wait();
			}
			catch(InterruptedException ex)
			{
			}
		    }
		    
		    _promote = false;
		}
		
		if(TRACE_THREAD)
		{
		    trace("thread " + Thread.currentThread() + " has the lock");
		}
	    }
        }
    }

    private void
    read(EventHandler handler)
    {
        BasicStream stream = handler._stream;

        if(stream.size() == 0)
        {
            stream.resize(Protocol.headerSize, true);
            stream.pos(0);
        }

        if(stream.pos() != stream.size())
        {
            handler.read(stream);
            assert(stream.pos() == stream.size());
        }

        int pos = stream.pos();
        assert(pos >= Protocol.headerSize);
        stream.pos(0);
	byte[] m = new byte[4];
	m[0] = stream.readByte();
	m[1] = stream.readByte();
	m[2] = stream.readByte();
	m[3] = stream.readByte();
	if(m[0] != Protocol.magic[0] || m[1] != Protocol.magic[1]
	   || m[2] != Protocol.magic[2] || m[3] != Protocol.magic[3])
	{
	    Ice.BadMagicException ex = new Ice.BadMagicException();
	    ex.badMagic = m;
	    throw ex;
	}

	byte pMajor = stream.readByte();
	byte pMinor = stream.readByte();
	if(pMajor != Protocol.protocolMajor || pMinor > Protocol.protocolMinor)
	{
	    Ice.UnsupportedProtocolException e = new Ice.UnsupportedProtocolException();
	    e.badMajor = pMajor < 0 ? pMajor + 255 : pMajor;
	    e.badMinor = pMinor < 0 ? pMinor + 255 : pMinor;
	    e.major = Protocol.protocolMajor;
	    e.minor = Protocol.protocolMinor;
	    throw e;
	}

	byte eMajor = stream.readByte();
	byte eMinor = stream.readByte();
	if(eMajor != Protocol.encodingMajor || eMinor > Protocol.encodingMinor)
	{
	    Ice.UnsupportedEncodingException e = new Ice.UnsupportedEncodingException();
	    e.badMajor = eMajor < 0 ? eMajor + 255 : eMajor;
	    e.badMinor = eMinor < 0 ? eMinor + 255 : eMinor;
	    e.major = Protocol.encodingMajor;
	    e.minor = Protocol.encodingMinor;
	    throw e;
	}

        byte messageType = stream.readByte();
        byte compress = stream.readByte();
        int size = stream.readInt();
        if(size < Protocol.headerSize)
        {
            throw new Ice.IllegalMessageSizeException();
        }
        if(size > _messageSizeMax)
        {
            throw new Ice.MemoryLimitException();
        }
        if(size > stream.size())
        {
            stream.resize(size, true);
        }
        stream.pos(pos);

        if(stream.pos() != stream.size())
	{
	    if(handler.datagram())
	    {
		if(_warnUdp)
		{
		    _instance.logger().warning("DatagramLimitException: maximum size of "
		                               + stream.pos() + " exceeded");
		}
		stream.pos(0);
		stream.resize(0, true);
		throw new Ice.DatagramLimitException();
	    }
	    else
	    {
		handler.read(stream);
		assert(stream.pos() == stream.size());
	    }
        }
    }

    private void
    selectNonBlocking()
    {
        while(true)
        {
            try
            {
                if(TRACE_SELECT)
                {
                    trace("non-blocking select on " + _selector.keys().size() + " keys, thread id = " +
                          Thread.currentThread());
                }

                _selector.selectNow();

                if(TRACE_SELECT)
                {
                    if(_keys.size() > 0)
                    {
                        trace("after selectNow, there are " + _keys.size() + " selected keys:");
                        java.util.Iterator i = _keys.iterator();
                        while(i.hasNext())
                        {
                            java.nio.channels.SelectionKey key = (java.nio.channels.SelectionKey)i.next();
                            trace("  " + keyToString(key));
                        }
                    }
                }

                break;
            }
            catch(java.io.InterruptedIOException ex)
            {
                continue;
            }
            catch(java.io.IOException ex)
            {
                //
                // Pressing Ctrl-C causes select() to raise an
                // IOException, which seems like a JDK bug. We trap
                // for that special case here and ignore it.
                // Hopefully we're not masking something important!
                //
                if(ex.getMessage().indexOf("Interrupted system call") != -1)
                {
                    continue;
                }

                Ice.SocketException se = new Ice.SocketException();
                se.initCause(ex);
                throw se;
            }
        }
    }

    private void
    select()
    {
        int ret = 0;

        while(true)
        {
            try
            {
                if(TRACE_SELECT)
                {
                    trace("select on " + _selector.keys().size() + " keys, thread id = " + Thread.currentThread());
                }

		if(_timeout > 0)
		{
		    ret = _selector.select(_timeout * 1000);
		}
		else
		{
		    ret = _selector.select();
		}
            }
            catch(java.io.InterruptedIOException ex)
            {
                continue;
            }
            catch(java.io.IOException ex)
            {
                //
                // Pressing Ctrl-C causes select() to raise an
                // IOException, which seems like a JDK bug. We trap
                // for that special case here and ignore it.
                // Hopefully we're not masking something important!
                //
                if(ex.getMessage().indexOf("Interrupted system call") != -1)
                {
                    continue;
                }

                Ice.SocketException se = new Ice.SocketException();
                se.initCause(ex);
                throw se;
            }

            if(TRACE_SELECT)
            {
                trace("select() returned " + ret + ", _keys.size() = " + _keys.size());
            }

            break;
        }
    }

    private void
    trace(String msg)
    {
        System.err.println(_prefix + ": " + msg);
    }

    private String
    keyToString(java.nio.channels.SelectionKey key)
    {
        String ops = "[";
        if(key.isAcceptable())
        {
            ops += " OP_ACCEPT";
        }
        if(key.isReadable())
        {
            ops += " OP_READ";
        }
        if(key.isConnectable())
        {
            ops += " OP_CONNECT";
        }
        if(key.isWritable())
        {
            ops += " OP_WRITE";
        }
        ops += " ]";
        return key.channel() + " " + ops;
    }

    private static final class FdHandlerPair
    {
        java.nio.channels.SelectableChannel fd;
        EventHandler handler;

        FdHandlerPair(java.nio.channels.SelectableChannel fd, EventHandler handler)
        {
            this.fd = fd;
            this.handler = handler;
        }
    }

    private static final class HandlerKeyPair
    {
        EventHandler handler;
        java.nio.channels.SelectionKey key;

        HandlerKeyPair(EventHandler handler, java.nio.channels.SelectionKey key)
        {
            this.handler = handler;
            this.key = key;
        }
    }

    private Instance _instance;
    private boolean _destroyed;
    private final String _prefix;
    private final String _programNamePrefix;

    private java.nio.channels.ReadableByteChannel _fdIntrRead;
    private java.nio.channels.SelectionKey _fdIntrReadKey;
    private java.nio.channels.WritableByteChannel _fdIntrWrite;
    private java.nio.channels.Selector _selector;
    private java.util.Set _keys;

    private java.util.LinkedList _changes = new java.util.LinkedList();

    private java.util.HashMap _handlerMap = new java.util.HashMap();

    private int _timeout;

    private final class EventHandlerThread extends Thread
    {
        EventHandlerThread(String name)
        {
            super(name);
        }

        public void
        run()
        {
            BasicStream stream = new BasicStream(_instance);

	    boolean promote;

            try
            {
		promote = ThreadPool.this.run(stream);
            }
            catch(Ice.LocalException ex)
            {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.flush();
                String s = "exception in `" + _prefix + "' thread " + getName() + ":\n" + sw.toString();
                _instance.logger().error(s);
		promote = true;
            }
            catch(Exception ex)
            {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.flush();
                String s = "unknown exception in `" + _prefix + "' thread " + getName() + ":\n" + sw.toString();
                _instance.logger().error(s);
		promote = true;
            }

	    if(promote && _sizeMax > 1)
	    {
		//
		// Promote a follower, but w/o modifying _inUse or
		// creating new threads.
		//
		synchronized(ThreadPool.this)
		{
		    assert(!_promote);
		    _promote = true;
		    ThreadPool.this.notify();
		}
	    }

            stream.destroy();

            if(TRACE_THREAD)
            {
                trace("run() terminated");
            }
        }
    }

    private final int _size; // Number of threads that are pre-created.
    private final int _sizeMax; // Maximum number of threads.
    private final int _sizeWarn; // If _inUse reaches _sizeWarn, a "low on threads" warning will be printed.

    private final int _messageSizeMax;

    private java.util.ArrayList _threads; // All threads, running or not.
    private int _threadIndex; // For assigning thread names.
    private int _running; // Number of running threads.
    private int _inUse; // Number of threads that are currently in use.
    private double _load; // Current load in number of threads.

    private boolean _promote;

    private boolean _warnUdp;
}

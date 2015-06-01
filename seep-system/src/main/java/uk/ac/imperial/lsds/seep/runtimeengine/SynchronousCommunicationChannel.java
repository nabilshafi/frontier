/*******************************************************************************
 * Copyright (c) 2013 Imperial College London.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Raul Castro Fernandez - initial design and implementation
 ******************************************************************************/
package uk.ac.imperial.lsds.seep.runtimeengine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.imperial.lsds.seep.GLOBALS;
import uk.ac.imperial.lsds.seep.buffer.IBuffer;
import uk.ac.imperial.lsds.seep.buffer.OutputLogEntry;
import uk.ac.imperial.lsds.seep.comm.serialization.messages.BatchTuplePayload;
import uk.ac.imperial.lsds.seep.comm.serialization.messages.TuplePayload;
import uk.ac.imperial.lsds.seep.operator.EndPoint;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;

/**
* OutputInformation. This class models the information associated to a downstream or upstream connection
*/
public class SynchronousCommunicationChannel implements EndPoint{

	private static final Logger logger = LoggerFactory.getLogger(SynchronousCommunicationChannel.class);
	private int targetOperatorId;
	private Socket downstreamDataSocket;
	private Socket downstreamControlSocket;
	private Socket blindSocket;
	private IBuffer buffer;
	
	private final Object controlSocketLock = new Object(){};
	
	private Output output = null;
	private OutputStream bos = null;
	
	//Set atomic variables to their initial value
	private AtomicBoolean stop = new AtomicBoolean(false);
	private AtomicBoolean replay = new AtomicBoolean(false);
	
	private TimestampTracker reconf_ts;
	private long last_ts;
	private Iterator<OutputLogEntry> sharedIterator;
	
	//Batch information for this channel
	private BatchTuplePayload batch = new BatchTuplePayload();
	private int channelBatchSize = Integer.parseInt(GLOBALS.valueFor("batchLimit"));
	private long tick = 0;
	private boolean deferredInit = false;
	private InetAddress deferredIp = null;
	private int deferredPortD = -1;
	private int deferredPortC = -1;

	public SynchronousCommunicationChannel(int opId, Socket downstreamSocketD, Socket downstreamSocketC, Socket blindSocket, IBuffer buffer){
		this.targetOperatorId = opId;
		this.downstreamDataSocket = downstreamSocketD;
		this.downstreamControlSocket = downstreamSocketC;
		this.blindSocket = blindSocket;
		this.buffer = buffer;
		try {
			/// \fixme{this must be fixed, different CONSTRUCTORS, please...}
			if(downstreamDataSocket != null){
				//Create buffered output stream
				//Create common outputstream and let kryo to manage buffers
				bos = downstreamSocketD.getOutputStream();
				//Create the kryo output for this socket
				output = new Output(bos);
			}
		}
		catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public SynchronousCommunicationChannel(int opId, InetAddress deferredIp, int deferredPortD, int deferredPortC, IBuffer buffer){	
		this.targetOperatorId = opId;
		this.buffer = buffer;
		this.deferredInit = true;
		this.deferredIp = deferredIp;
		this.deferredPortD = deferredPortD;
		this.deferredPortC = deferredPortC;
	}
	
	public int getOperatorId(){
		return targetOperatorId;
	}
	
	public Socket getDownstreamControlSocket() {
		synchronized(controlSocketLock)
		{
			while (downstreamControlSocket == null)
			{
				try
				{
					controlSocketLock.wait(30*1000);
				}
				catch(InterruptedException e) { e.printStackTrace(); /*Urgh*/}
			}
			return downstreamControlSocket;
		}
	}
	
	public Socket tryGetDownstreamControlSocket()
	{
		synchronized(controlSocketLock)
		{
			return downstreamControlSocket;
		}
	}
	
	public Socket getBlindSocket(){
		return blindSocket;
	}
	
	public Socket reOpenBlindSocket(){
		InetAddress ip = blindSocket.getInetAddress();
		int port = blindSocket.getPort();
		try {
			blindSocket = new Socket(ip, port);
		} 
		catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return blindSocket;
	}
	
	/** ThreadSafety: This method, getOutput(), and getDownstreamDataSocket()
	 * should only ever be called with the corresponding OutputQueue lock held
	 * to avoid concurrent data consumer/processing threads trying to reopen a failed socket.
	 * dokeeffe TODO: Currently this will just block indefinitely until the create socket call
	 * succeeds. Probably want to add a way to interrupt the waiting (e.g. if we need
	 * to scale-in/reconfigure/shutdown).
	 * @return the new socket.
	 */
	public Socket reopenDownstreamDataSocket()
	{	
		logger.info("Reopening downstream data socket");
		InetAddress ip = null;
		int port = -1;
		if (downstreamDataSocket != null)
		{			
			ip = downstreamDataSocket.getInetAddress();
			port = downstreamDataSocket.getPort();
			logger.info("Existing downstream data socket not null: "+ip);
			//Try to close the current downstream data socket and output stream
			try { output.close(); } 
			catch (KryoException e) { e.printStackTrace(); } 
			try { downstreamDataSocket.close(); } 
			catch (IOException e) {e.printStackTrace();}
		}
		else if (downstreamDataSocket == null && deferredInit) 
		{ 
			logger.info("Trying a deferred init of data channel to "+deferredIp);
			ip = deferredIp;
			port = deferredPortD;
		}
		else
		{
			throw new RuntimeException("No data socket on this channel."); 
		}
		output = null;
		downstreamDataSocket = null;

		int numReconnects = 0;
		boolean success = false;
		while(!success)
		{
			Socket tmpSocket = null;
			OutputStream tmpOutput = null;
			try
			{
				tmpSocket = new Socket(ip, port);
				tmpOutput = tmpSocket.getOutputStream();
				downstreamDataSocket = tmpSocket;
				output = new Output(tmpOutput);
				success = true;
			}
			catch(IOException e)
			{
				if (tmpSocket != null) 
				{ 
					try { tmpSocket.close(); } 
					catch (IOException e1) {} 
				}
				if (numReconnects < 1 || numReconnects % 100 == 0)
				{
					logger.error("Data connection "+ numReconnects+" to "+ip+" failed: "+e);					
				}
				else
				{
					logger.debug("Data connection "+ numReconnects+" to "+ip+" failed: "+e);
				}
				numReconnects++;
			}
		}
		logger.info("Successfully connected data channel to "+downstreamDataSocket.getInetAddress());
		try
		{
			logger.info("Socket send buffer size= "+downstreamDataSocket.getSendBufferSize());
			downstreamDataSocket.setSendBufferSize(Integer.parseInt(GLOBALS.valueFor("socketBufferSize")));
			logger.info("Socket set send buffer size= "+downstreamDataSocket.getSendBufferSize());
		}
		catch(SocketException e) {logger.error("Error reading buffer size:"+e); }
		return downstreamDataSocket;
	}
	
	/** Only call once, and only if the deferred init constructor was used */
	public void deferredInit()
	{
		logger.info("Starting deferred init to "+deferredIp);
		if (!deferredInit) { throw new RuntimeException("Logic error."); }
		deferredInitDownstreamControlSocket();
	}
	
	private void deferredInitDownstreamControlSocket()
	{				
		if (deferredPortC <= 0) { return; }
		openDownstreamControlSocketNonBlocking(deferredIp, deferredPortC);
	}
	
	
	/** 
	 * dokeeffe TODO: Should probably allow this to be 
	 * extended to support cancellation. At the moment it will
	 * try to reconnect for ever.
	 */	 
	public void reopenDownstreamControlSocketNonBlocking(Socket prevSocketToClose)
	{
		if (prevSocketToClose == null) 
		{ 
			if (!deferredInit)
			{
				//Temp sanity check, should probably remove this restriction.
				throw new RuntimeException("Previous socket should never be null.");
			}
			else
			{
				return;
			}
		}
		
		final InetAddress ip;
		final int port;
		
		synchronized(controlSocketLock)
		{
			if (downstreamControlSocket != null && prevSocketToClose == downstreamControlSocket)
			{
				ip = downstreamControlSocket.getInetAddress();
				port = downstreamControlSocket.getPort();			
				downstreamControlSocket = null;
			}
			else
			{
				try { prevSocketToClose.close(); }
				catch(IOException e) {
					e.printStackTrace(); /*Urgh*/ 
				}
				logger.info("Another caller already reopening control socket.");
				//Another caller is already reopening the socket.
				return;
			}			
		}

		openDownstreamControlSocketNonBlocking(ip, port);		
	}
	
	private void openDownstreamControlSocketNonBlocking(final InetAddress ip, final int port)
	{
		new Thread(new Runnable() 
		{
			public void run()
			{
				int reconnectCount = 0;
				while(true)
				{
					Socket tmpSocket = null;
					try
					{
						tmpSocket = new Socket(ip, port);
						synchronized(controlSocketLock)
						{
							downstreamControlSocket = tmpSocket;
							controlSocketLock.notifyAll();
							logger.info("Successfully connected to control socket "+ip+":"+port);
							return;
							//return downstreamControlSocket;
						}
					}
					catch(IOException e)
					{
						if (reconnectCount < 1)
						{
							e.printStackTrace(); // Urgh
						}
						//e.printStackTrace();
					}
					reconnectCount++;
					//dokeeffe TODO: N.B. If some other exception causes this
					//thread to fail then this connection will be stuck forever
					//as there is no way for another thread to restart the connection
					//currently
				}
			}
		}).start();
	}
	
	
	
	public void setSharedIterator(Iterator<OutputLogEntry> i){
		this.sharedIterator = i;
	}
	
	public Iterator<OutputLogEntry> getSharedIterator(){
		return sharedIterator;
	}
	
	public Output getOutput() {
		return output;
	}
	
	public void setTick(long tick){
		this.tick = tick;
	}
	
	public Socket getDownstreamDataSocket(){
		return downstreamDataSocket;
	}
	
	public IBuffer getBuffer(){
		return buffer;
	}
	
	public AtomicBoolean getReplay(){
		return replay;
	}
	
	//dokeeffe TODO: Hmm, might want to keep this internal
	// and add an extra setter to the interface if this class
	// is going to handle reconnects.
	public AtomicBoolean getStop(){
		return stop;
	}
	
	public synchronized BatchTuplePayload getBatch(){
		return batch;
	}
	
	public synchronized void addDataToBatch(TuplePayload payload){
		batch.addTuple(payload);
		channelBatchSize--;
		last_ts = payload.timestamp;
	}
	
	public int getChannelBatchSize(){
		return channelBatchSize;
	}
	
	public void resetChannelBatchSize(){
		channelBatchSize = 0;
	}
	
	public void cleanBatch(){
		batch.clear();
		int limit = Integer.parseInt(GLOBALS.valueFor("batchLimit"));
		channelBatchSize = limit;
	}
	
	public void cleanBatch2(){
		batch = new BatchTuplePayload();
	}
	
	public long getLast_ts(){
		return last_ts;
	}
	
	public TimestampTracker getReconf_ts(){
		return reconf_ts;
	}
	
	public void setReconf_ts(TimestampTracker ts){
		this.reconf_ts = ts;
	}
}

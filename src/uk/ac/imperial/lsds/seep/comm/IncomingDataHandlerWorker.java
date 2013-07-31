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
package uk.ac.imperial.lsds.seep.comm;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Map;

import uk.ac.imperial.lsds.seep.comm.serialization.DataTuple;
import uk.ac.imperial.lsds.seep.comm.serialization.messages.BatchTuplePayload;
import uk.ac.imperial.lsds.seep.comm.serialization.messages.Payload;
import uk.ac.imperial.lsds.seep.comm.serialization.messages.TuplePayload;
import uk.ac.imperial.lsds.seep.comm.serialization.serializers.ArrayListSerializer;
import uk.ac.imperial.lsds.seep.infrastructure.NodeManager;
import uk.ac.imperial.lsds.seep.runtimeengine.CoreRE;
import uk.ac.imperial.lsds.seep.runtimeengine.DataStructureAdapter;
import uk.ac.imperial.lsds.seep.runtimeengine.DataStructureI;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;

public class IncomingDataHandlerWorker implements Runnable{

	private Socket upstreamSocket = null;
	private CoreRE owner = null;
	private boolean goOn;
	private Map<String, Integer> idxMapper;
	private Kryo k = null;
	
	public IncomingDataHandlerWorker(Socket upstreamSocket, CoreRE owner, Map<String, Integer> idxMapper){
		//upstream id
		this.upstreamSocket = upstreamSocket;
		this.owner = owner;
		this.goOn = true;
		this.idxMapper = idxMapper;
		this.k = initializeKryo();
	}
	
	private Kryo initializeKryo(){
		//optimize here kryo
		Kryo k = new Kryo();
		k.setClassLoader(owner.getRuntimeClassLoader());
		
		k.register(ArrayList.class, new ArrayListSerializer());
		k.register(Payload.class);
		k.register(TuplePayload.class);
		k.register(BatchTuplePayload.class);
		return k;
	}
	
	public void run() {
		
		/** Experimental asyn **/
//		try{
//			//Get inputQueue from owner
//			DataStructureAdapter dsa = owner.getDSA();
//			//Get inputStream of incoming connection
//			InputStream is = upstreamSocket.getInputStream();
//			BufferedInputStream bis = new BufferedInputStream(is, 8192);
//			Input i = new Input(bis);
//
//			while(goOn){
//				TuplePayload tp = k.readObject(i, TuplePayload.class);
//				DataTuple reg = new DataTuple(idxMapper, tp);
//				dsa.push(reg);
//			}
//		}
//		catch(IOException io){
//			NodeManager.nLogger.severe("-> IncDataHandlerWorker. IO Error "+io.getMessage());
//			io.printStackTrace();
//		}
		

		
		/** experimental sync **/
		try{
			//Get bridge adapter from owner
			DataStructureAdapter dsa = owner.getDSA();
			// Get incomingOp id
			int opId = owner.getOpIdFromInetAddress(((InetSocketAddress)upstreamSocket.getRemoteSocketAddress()).getAddress());
			int originalOpId = owner.getOriginalUpstreamFromOpId(opId);
			
			DataStructureI dso = null;
			if(dsa.getUniqueDso() != null){
				dso = dsa.getUniqueDso();
				NodeManager.nLogger.info("-> Unique data adapter in this node");
			}
			else{
				dso = dsa.getDataStructureIForOp(originalOpId);
				NodeManager.nLogger.info("-> Multiple data adapters in this node");
			}
			//Get inputStream of incoming connection
			InputStream is = upstreamSocket.getInputStream();
			BufferedInputStream bis = new BufferedInputStream(is);
			Input i = new Input(bis);
			BatchTuplePayload batchTuplePayload = null;

			while(goOn){
				batchTuplePayload = k.readObject(i, BatchTuplePayload.class);
				ArrayList<TuplePayload> batch = batchTuplePayload.batch;
				for(TuplePayload t_payload : batch){
					long incomingTs = t_payload.timestamp;
					owner.setTsData(incomingTs);
//System.out.println("new data ts: "+incomingTs);
					//Put data in inputQueue
					if(owner.checkSystemStatus()){
						DataTuple reg = new DataTuple(idxMapper, t_payload);
						dso.push(reg);
					}
					else{
//						System.out.println("trash in TCP buffers");
					}
				}
			}
			NodeManager.nLogger.severe("-> Data connection closing...");
			upstreamSocket.close();
		}
		catch(IOException io){
			NodeManager.nLogger.severe("-> IncDataHandlerWorker. IO Error "+io.getMessage());
			io.printStackTrace();
		}
	}
}

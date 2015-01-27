/*******************************************************************************
 * Copyright (c) 2014 Imperial College London
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Raul Castro Fernandez - initial API and implementation
 ******************************************************************************/
package uk.ac.imperial.lsds.seep.acita15.operators;

import java.util.List;

import uk.ac.imperial.lsds.seep.comm.serialization.DataTuple;
import uk.ac.imperial.lsds.seep.operator.StatelessOperator;

public class Sink implements StatelessOperator {

	private static final long serialVersionUID = 1L;
	
	public void setUp() {

	}

	// time control variables
	int c = 0;
	long init = 0;
	int sec = 0;
	int totalData = 0;
	
	public void processData(DataTuple dt) {
		System.out.println("Sink received "+dt.toString());
		int value2 = dt.getInt("value2");
		// TIME CONTROL
		totalData++;
		if((System.currentTimeMillis() - init) > 1000){
			System.out.println("SNK: "+sec+" "+c+" ");
			c = 0;
			sec++;
			init = System.currentTimeMillis();
		}
		if (totalData > 10)
		{
			System.out.println("SNK: FINISHED with total data="+totalData);
			System.exit(0);
		}
	}
	
	public void processData(List<DataTuple> arg0) {
	}
}

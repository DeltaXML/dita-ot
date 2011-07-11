/*
 * This file is part of the DITA Open Toolkit project hosted on
 * Sourceforge.net. See the accompanying license.txt file for 
 * applicable licenses.
 */

/*
 * (c) Copyright IBM Corp. 2010 All Rights Reserved.
 */
package org.dita.dost.util;

import org.dita.dost.log.DITAOTJavaLogger;

/**
 * Version Utility class, providing method of getting version 
 * information to AntVersion.java
 * @author william
 * 
 */
public final class VersionUtil {

	private final String otversion = "@@OTVERSION@@";
	

	/**
	 * @return the milestone
	 * @deprecated use {@link #getOtversion()} instead. To be remove in future releases
	 */
	@Deprecated
	public String getMilestone() {
		return "";
	}

	/**
	 * @return the otversion
	 */
	public String getOtversion() {
		return "DITA Open Toolkit " + otversion;
	}
	
	public VersionUtil() {
		
	}

}
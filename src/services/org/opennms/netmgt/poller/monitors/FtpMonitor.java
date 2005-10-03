//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2002-2003 The OpenNMS Group, Inc. All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified
// and included code are below.
//
// OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
//
// Modifications:
//
// 2004 May 05: Switch from SocketChannel to Socket with connection timeout.
// 2003 Jul 21: Explicitly closed socket.
// 2003 Jul 18: Enabled retries for monitors.
// 2003 Jun 11: Added a "catch" for RRD update errors. Bug #748.
// 2003 Jan 31: Added the ability to imbed RRA information in poller packages.
// 2003 Jan 31: Cleaned up some unused imports.
// 2003 Jan 29: Added response times to certain monitors.
// 2002 Nov 14: Used non-blocking I/O socket channel classes.
//
// Original code base Copyright (C) 1999-2001 Oculan Corp. All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//
// For more information contact:
//      OpenNMS Licensing <license@opennms.org>
//      http://www.opennms.org/
//      http://www.opennms.com/
//
// Tab Size = 8
//

package org.opennms.netmgt.poller.monitors;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.log4j.Category;
import org.apache.regexp.RE;
import org.apache.regexp.RESyntaxException;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.config.poller.Package;
import org.opennms.netmgt.poller.ServiceMonitor;
import org.opennms.netmgt.poller.pollables.PollStatus;
import org.opennms.netmgt.utils.ParameterMap;

/**
 * This class is designed to be used by the service poller framework to test the
 * availability of the FTP service on remote interfaces. The class implements
 * the ServiceMonitor interface that allows it to be used along with other
 * plug-ins by the service poller framework.
 * 
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 * 
 * 
 */
final public class FtpMonitor extends IPv4LatencyMonitor {

    /**
     * Default FTP port.
     */
    private static final int DEFAULT_PORT = 21;

    /**
     * Default retries.
     */
    private static final int DEFAULT_RETRY = 0;

    /**
     * Default timeout. Specifies how long (in milliseconds) to block waiting
     * for data from the monitored interface.
     */
    private static final int DEFAULT_TIMEOUT = 3000; // 3 second timeout on
                                                        // read()

    /**
     * Specific error message generated by some FTP servers when a QUIT is
     * issued by a client when the client has not successfully logged in.
     */
    private static final String FTP_ERROR_530_TEXT = "User not logged in. Please login with USER and PASS first";

    /**
     * Specific error message generated by some FTP servers when a QUIT is
     * issued by a client when the client has not successfully logged in.
     */
    private static final String FTP_ERROR_425_TEXT = "425 Session is disconnected.";

    /**
     * Used to check for a multiline response. A multline response begins with
     * the same 3 digit response code, but has a hypen after the last number
     * instead of a space.
     */
    private static RE MULTILINE = null;

    /**
     * Used to check for the end of a multiline response. The end of a multiline
     * response is the same 3 digit response code followed by a space
     */
    private RE ENDMULTILINE = null;

    static {
        try {
            MULTILINE = new RE("^[0-9]{3}-");
        } catch (RESyntaxException ex) {
            throw new java.lang.reflect.UndeclaredThrowableException(ex);
        }
    }

    /**
     * Poll the specified address for FTP service availability.
     * 
     * During the poll an attempt is made to connect on the specified port (by
     * default TCP port 21). If the connection request is successful, the banner
     * line generated by the interface is parsed and if the extracted return
     * code indicates that we are talking to an FTP server we continue. Next, an
     * FTP 'QUIT' command is sent. Provided that the interface's response is
     * valid we set the service status to SERVICE_AVAILABLE and return.
     * 
     * @param iface
     *            The network interface to test the service on.
     * @param parameters
     *            The package parameters (timeout, retry, etc...) to be used for
     *            this poll.
     * 
     * @return The availibility of the interface and if a transition event
     *         should be supressed.
     * 
     */
    public int checkStatus(NetworkInterface iface, Map parameters, org.opennms.netmgt.config.poller.Package pkg) {
        // check the interface type
        //
        if (iface.getType() != NetworkInterface.TYPE_IPV4)
            throw new NetworkInterfaceNotSupportedException("Unsupported interface type, only TYPE_IPV4 currently supported");

        // Get the category logger
        //
        Category log = ThreadCategory.getInstance(getClass());

        // get the parameters
        //
        int retry = ParameterMap.getKeyedInteger(parameters, "retry", DEFAULT_RETRY);
        int port = ParameterMap.getKeyedInteger(parameters, "port", DEFAULT_PORT);
        int timeout = ParameterMap.getKeyedInteger(parameters, "timeout", DEFAULT_TIMEOUT);
        String userid = ParameterMap.getKeyedString(parameters, "userid", null);
        String password = ParameterMap.getKeyedString(parameters, "password", null);
        String rrdPath = ParameterMap.getKeyedString(parameters, "rrd-repository", null);
        String dsName = ParameterMap.getKeyedString(parameters, "ds-name", null);

        if (rrdPath == null) {
            log.info("poll: RRD repository not specified in parameters, latency data will not be stored.");
        }
        if (dsName == null) {
            dsName = DEFAULT_DSNAME;
        }

        // Extract the address
        //
        InetAddress ipv4Addr = (InetAddress) iface.getAddress();

        if (log.isDebugEnabled())
            log.debug("FtpMonitor.poll: Polling interface: " + ipv4Addr.getHostAddress() + " timeout: " + timeout + " retry: " + retry);

        int serviceStatus = ServiceMonitor.SERVICE_UNAVAILABLE;
        long responseTime = -1;
        for (int attempts = 0; attempts <= retry && serviceStatus != ServiceMonitor.SERVICE_AVAILABLE; attempts++) {
            Socket socket = null;
            try {
                //
                // create a connected socket
                //
                long sentTime = System.currentTimeMillis();

                socket = new Socket();
                socket.connect(new InetSocketAddress(ipv4Addr, port), timeout);
                socket.setSoTimeout(timeout);

                log.debug("FtpMonitor: connected to host: " + ipv4Addr + " on port: " + port);
                // We're connected, so upgrade status to unresponsive
                serviceStatus = SERVICE_UNRESPONSIVE;

                BufferedReader lineRdr = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // Tokenize the Banner Line, and check the first
                // line for a valid return.
                //
                String banner = lineRdr.readLine();
                responseTime = System.currentTimeMillis() - sentTime;

                if (banner == null)
                    continue;
                if (MULTILINE.match(banner)) {
                    // Ok we have a multi-line response...first three
                    // chars of the response line are the return code.
                    // The last line of the response will start with
                    // return code followed by a space.
                    String multiLineRC = new String(banner.getBytes(), 0, 3) + " ";

                    // Create new regExp to look for last line
                    // of this mutli line response
                    try {
                        ENDMULTILINE = new RE(multiLineRC);
                    } catch (RESyntaxException ex) {
                        throw new java.lang.reflect.UndeclaredThrowableException(ex);
                    }

                    // read until we hit the last line of the multi-line
                    // response
                    do {
                        banner = lineRdr.readLine();
                    } while (banner != null && !ENDMULTILINE.match(banner));
                    if (banner == null)
                        continue;
                }

                StringTokenizer t = new StringTokenizer(banner);

                int rc = -1;
                try {
                    rc = Integer.parseInt(t.nextToken());
                } catch (NumberFormatException nfE) {
                    nfE.fillInStackTrace();
                    log.warn("Banner page returned invalid result code", nfE);
                }

                // Verify that return code is in proper range.
                //
                if (rc >= 200 && rc <= 299) {
                    // 
                    // Attempt to login if userid and password available
                    //
                    boolean bLoginOk = false;
                    if (userid == null || userid.length() == 0 || password == null || password.length() == 0) {
                        bLoginOk = true;
                    } else {
                        // send the use string
                        //
                        String cmd = "user " + userid + "\r\n";
                        socket.getOutputStream().write(cmd.getBytes());

                        // get the response code.
                        //
                        String response = null;
                        do {
                            response = lineRdr.readLine();
                        } while (response != null && MULTILINE.match(response));
                        if (response == null)
                            continue;

                        t = new StringTokenizer(response);
                        rc = Integer.parseInt(t.nextToken());

                        // Verify that return code is in proper range.
                        //
                        if (rc >= 200 && rc <= 399) {
                            // send the password
                            //
                            cmd = "pass " + password + "\r\n";
                            socket.getOutputStream().write(cmd.getBytes());

                            // get the response...check for multi-line response
                            //
                            response = lineRdr.readLine();
                            if (response == null)
                                continue;

                            if (MULTILINE.match(response)) {
                                // Ok we have a multi-line response...first
                                // three
                                // chars of the response line are the return
                                // code.
                                // The last line of the response will start with
                                // return code followed by a space.
                                String multiLineRC = new String(response.getBytes(), 0, 3) + " ";

                                // Create new regExp to look for last line
                                // of this mutli line response
                                try {
                                    ENDMULTILINE = new RE(multiLineRC);
                                } catch (RESyntaxException ex) {
                                    throw new java.lang.reflect.UndeclaredThrowableException(ex);
                                }

                                // read until we hit the last line of the
                                // multi-line
                                // response
                                do {
                                    response = lineRdr.readLine();
                                } while (response != null && !ENDMULTILINE.match(response));
                                if (response == null)
                                    continue;
                            }

                            // Verify that return code is in proper range.
                            //
                            if (log.isDebugEnabled())
                                log.debug("FtpMonitor.poll: tokenizing respone to check for return code: " + response);
                            t = new StringTokenizer(response);
                            rc = Integer.parseInt(t.nextToken());
                            if (rc >= 200 && rc <= 299) {
                                if (log.isDebugEnabled())
                                    log.debug("FtpMonitor.poll: Login successful, parsed return code: " + rc);
                                bLoginOk = true;
                            } else {
                                if (log.isDebugEnabled())
                                    log.debug("FtpMonitor.poll: Login failed, parsed return code: " + rc);
                                bLoginOk = false;
                            }
                        }
                    }

                    if (bLoginOk) {
                        // FTP should recognize the QUIT command
                        //
                        String cmd = "QUIT\r\n";
                        socket.getOutputStream().write(cmd.getBytes());

                        // get the returned string, tokenize, and
                        // verify the correct output.
                        //
                        String response = lineRdr.readLine();
                        if (response == null)
                            continue;
                        if (MULTILINE.match(response)) {
                            // Ok we have a multi-line response...first three
                            // chars of the response line are the return code.
                            // The last line of the response will start with
                            // return code followed by a space.
                            String multiLineRC = new String(response.getBytes(), 0, 3) + " ";

                            // Create new regExp to look for last line
                            // of this mutli line response
                            try {
                                ENDMULTILINE = new RE(multiLineRC);
                            } catch (RESyntaxException ex) {
                                throw new java.lang.reflect.UndeclaredThrowableException(ex);
                            }

                            // read until we hit the last line of the multi-line
                            // response
                            do {
                                response = lineRdr.readLine();
                            } while (response != null && !ENDMULTILINE.match(response));

                            if (response == null)
                                continue;
                        }

                        t = new StringTokenizer(response);
                        rc = Integer.parseInt(t.nextToken());

                        // Verify that return code is in proper range.
                        //

                        if (rc >= 200 && rc <= 299) {
                            serviceStatus = ServiceMonitor.SERVICE_AVAILABLE;
                            // Store response time in RRD
                            if (responseTime >= 0 && rrdPath != null) {
                                try {
                                    this.updateRRD(rrdPath, ipv4Addr, dsName, responseTime, pkg);
                                } catch (RuntimeException rex) {
                                    log.debug("There was a problem writing the RRD:" + rex);
                                }
                            }
                        }
                        // Special Case: Also want to accept the following ERROR
                        // message
                        // generated by some FTP servers following a QUIT
                        // command without
                        // a previously successful login:
                        //
                        // "530 QUIT : User not logged in. Please login with
                        // USER and PASS
                        // first."
                        //
                        else if (rc == 530 && response.indexOf(FTP_ERROR_530_TEXT) != -1) {
                            serviceStatus = ServiceMonitor.SERVICE_AVAILABLE;
                            // Store response time in RRD
                            if (responseTime >= 0 && rrdPath != null) {
                                try {
                                    this.updateRRD(rrdPath, ipv4Addr, dsName, responseTime, pkg);
                                } catch (RuntimeException rex) {
                                    log.debug("There was a problem writing the RRD:" + rex);
                                }
                            }
                        }
                        // Special Case: Also want to accept the following ERROR
                        // message
                        // generated by some FTP servers following a QUIT
                        // command without
                        // a previously successful login:
                        //
                        // "425 Session is disconnected."
                        //
                        else if (rc == 425 && response.indexOf(FTP_ERROR_425_TEXT) != -1) {
                            serviceStatus = ServiceMonitor.SERVICE_AVAILABLE;
                            // Store response time in RRD
                            if (responseTime >= 0 && rrdPath != null) {
                                try {
                                    this.updateRRD(rrdPath, ipv4Addr, dsName, responseTime, pkg);
                                } catch (RuntimeException rex) {
                                    log.debug("There was a problem writing the RRD:" + rex);
                                }
                            }
                        }
                    }
                }

                // If we get this far and the status has not been set
                // to available, then something didn't verify during
                // the banner checking or login/QUIT command process.
                if (serviceStatus != ServiceMonitor.SERVICE_AVAILABLE) {
                    serviceStatus = ServiceMonitor.SERVICE_UNAVAILABLE;
                }
            } catch (NumberFormatException e) {
                // Ignore
                e.fillInStackTrace();
                log.info("FtpMonitor.poll: NumberFormatException while polling address: " + ipv4Addr, e);
            } catch (NoRouteToHostException e) {
                e.fillInStackTrace();
                log.warn("FtpMonitor.poll: No route to host exception for address: " + ipv4Addr, e);
                break; // Break out of for(;;)
            } catch (InterruptedIOException e) {
                // Ignore
                log.debug("FtpMonitor: did not connect to host within timeout: " + timeout + " attempt: " + attempts);
            } catch (ConnectException e) {
                // Connection refused. Continue to retry.
                e.fillInStackTrace();
                log.debug("FtpMonitor.poll: Connection exception for address: " + ipv4Addr, e);
            } catch (IOException e) {
                // Ignore
                e.fillInStackTrace();
                log.debug("FtpMonitor.poll: IOException while polling address: " + ipv4Addr, e);
            } finally {
                try {
                    // Close the socket
                    if (socket != null)
                        socket.close();
                } catch (IOException e) {
                    e.fillInStackTrace();
                    log.debug("FtpMonitor.poll: Error closing socket.", e);
                }
            }
        }

        //
        // return the status of the service
        //
        return serviceStatus;
    }

    public PollStatus poll(NetworkInterface iface, Map parameters, Package pkg) {
        return PollStatus.getPollStatus(checkStatus(iface, parameters, pkg));
    }

}

// Copyright 2011 Google Inc. All Rights Reserved.
package com.google.wireless.speed.speedometer;

import com.google.wireless.speed.speedometer.util.MeasurementJsonConvertor;
import com.google.wireless.speed.speedometer.util.RuntimeUtil;

import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

/**
 * Handles checkins with the SpeedometerApp server.
 * 
 * @author mdw@google.com (Matt Welsh)
 * @author wenjiezeng@google.com (Wenjie Zeng)
 */
public class Checkin {
  private SpeedometerApp speedometer;
  private String serverUrl;
  private Date lastCheckin;
  private volatile Cookie authCookie = null;
  private AsyncTask<String, Void, Cookie> getCookieTask = null;
  
  public Checkin(SpeedometerApp speedometer, String serverUrl) {
    this.speedometer = speedometer;
    this.serverUrl = serverUrl;
    sendStringMsg("Server: " + this.serverUrl);
  }
  
  public Checkin(SpeedometerApp speedometer) {
    this.speedometer = speedometer;
    this.serverUrl = speedometer.getResources().getString(
        R.string.SpeedometerServerURL);
    sendStringMsg("Server: " + this.serverUrl);
  }
  
  /** Returns whether the service is running on a testing server. */
  public boolean isTestingServer() {
    if (serverUrl.indexOf("corp.google.com") > 0) {
      return true;
    } else {
      return false;
    }
  }
  
  /** Return a fake authentication cookie for a test server instance */
  private Cookie getFakeAuthCookie() {
    BasicClientCookie cookie = new BasicClientCookie(
        "dev_appserver_login",
        "test@nobody.com:False:185804764220139124118");
    cookie.setDomain(".google.com");
    cookie.setVersion(1);
    cookie.setPath("/");
    cookie.setSecure(false);
    return cookie;
  }
  
  public Date lastCheckinTime() {
    return this.lastCheckin;
  }
  
  public String getServerUrl() {
    return serverUrl;
  }
  
  public List<MeasurementTask> checkin() throws IOException {
    Log.i(SpeedometerApp.TAG, "Checkin.checkin() called");
    try {
      JSONObject status = new JSONObject();
      DeviceInfo info = RuntimeUtil.getDeviceInfo();
      // TODO(Wenjie): There is duplicated info here, such as device ID. 
      status.put("id", info.deviceId);
      status.put("manufacturer", info.manufacturer);
      status.put("model", info.model);
      status.put("os", info.os);
      status.put("properties", 
          MeasurementJsonConvertor.encodeToJson(RuntimeUtil.getDeviceProperty()));
      
      Log.i(SpeedometerApp.TAG, status.toString());
      
      String result = speedometerServiceRequest("checkin", status.toString());
      Log.i(SpeedometerApp.TAG, "Checkin result: " + result);
      
      // Parse the result
      Vector<MeasurementTask> schedule = new Vector<MeasurementTask>();
      JSONArray jsonArray = new JSONArray(result);
      
      for (int i = 0; i < jsonArray.length(); i++) {
        Log.i(SpeedometerApp.TAG, "Parsing index " + i);
        JSONObject json = jsonArray.optJSONObject(i);
        Log.i(SpeedometerApp.TAG, "Value is " + json);
        if (json != null) {
          try {
            MeasurementTask task = 
                MeasurementJsonConvertor.makeMeasurementTaskFromJson(json, this.speedometer);
            Log.i(SpeedometerApp.TAG, MeasurementJsonConvertor.toJsonString(task.measurementDesc));
            schedule.add(task);
          } catch (IllegalArgumentException e) {
            Log.w(SpeedometerApp.TAG, "Could not create task from JSON: " + e);
            // Just skip it, and try the next one
          }
        }
      }
      
      this.lastCheckin = new Date();
      Log.i(SpeedometerApp.TAG, "Checkin complete, got " + schedule.size() +
          " new tasks");
      return schedule;
      
    } catch (Exception e) {
      Log.e(SpeedometerApp.TAG, "Got exception during checkin: " + Log.getStackTraceString(e));
      throw new IOException(e.getMessage());
    }
  }
  
  public void uploadMeasurementResult(MeasurementResult result)
  throws IOException {
    Log.i(SpeedometerApp.TAG, "TaskSchedule.uploadMeasurementResult() called");
        
    String response = 
      speedometerServiceRequest("postmeasurement", MeasurementJsonConvertor.toJsonString(result));
    try {
      JSONObject responseJson = new JSONObject(response);
      if (!responseJson.getBoolean("success")) {
        throw new IOException("Failure posting measurement result");
      }
    } catch (JSONException e) {
      throw new IOException(e.getMessage());
    }
    Log.i(SpeedometerApp.TAG, "TaskSchedule.uploadMeasurementResult() complete");
  }
  
  @SuppressWarnings("unused")
  private String speedometerServiceRequest(String url)
      throws ClientProtocolException, IOException {
    
    synchronized (this) {
      if (authCookie == null) {
        if (!checkGetCookie()) {
          throw new IOException("No authCookie yet");
        }
      }
    }
    
    DefaultHttpClient client = new DefaultHttpClient();
    // TODO(mdw): For some reason this is not sending the cookie to the
    // test server, probably because the cookie itself is not properly
    // initialized. Below I manually set the Cookie header instead.
    CookieStore store = new BasicCookieStore();
    store.addCookie(authCookie);
    client.setCookieStore(store);
    Log.i(SpeedometerApp.TAG, "authCookie is: " + authCookie);
    
    String fullurl = serverUrl + "/" + url;
    HttpGet getMethod = new HttpGet(fullurl);
    // TODO(mdw): This should not be needed
    getMethod.addHeader("Cookie", authCookie.getName() + "=" + authCookie.getValue());
    
    ResponseHandler<String> responseHandler = new BasicResponseHandler();
    String result = client.execute(getMethod, responseHandler);
    return result;
  }
  
  private String speedometerServiceRequest(String url, String jsonString) 
    throws IOException {
    
    synchronized (this) {
      if (authCookie == null) {
        if (!checkGetCookie()) {
          throw new IOException("No authCookie yet");
        }
      }
    }
    
    DefaultHttpClient client = new DefaultHttpClient();
    // TODO(mdw): For some reason this is not sending the cookie to the
    // test server, probably because the cookie itself is not properly
    // initialized. Below I manually set the Cookie header instead.
    CookieStore store = new BasicCookieStore();
    store.addCookie(authCookie);
    client.setCookieStore(store);
    Log.i(SpeedometerApp.TAG, "authCookie is: " + authCookie);
    
    String fullurl = serverUrl + "/" + url;
    HttpPost postMethod = new HttpPost(fullurl);
    
    StringEntity se;
    try {
      se = new StringEntity(jsonString);
    } catch (UnsupportedEncodingException e) {
      throw new IOException(e.getMessage());
    }
    postMethod.setEntity(se);
    postMethod.setHeader("Accept", "application/json");
    postMethod.setHeader("Content-type", "application/json");
    // TODO(mdw): This should not be needed
    postMethod.setHeader("Cookie", authCookie.getName() + "=" + authCookie.getValue());

    ResponseHandler<String> responseHandler = new BasicResponseHandler();
    String result = client.execute(postMethod, responseHandler);
    return result;
  }
  
  /**
   * Initiate process to get the authorization cookie for the user account.
   * Returns immediately.
   */
  public synchronized void getCookie() {
    if (isTestingServer()) {
      Log.i(SpeedometerApp.TAG, "Setting fakeAuthCookie");
      authCookie = getFakeAuthCookie();
      return;
    }
    if (getCookieTask == null) {
      try {
        getCookieTask = new AccountSelector(speedometer, this).authorize();
      } catch (OperationCanceledException e) {
        Log.e(SpeedometerApp.TAG, "Unable to get auth cookie", e);
      } catch (AuthenticatorException e) {
        Log.e(SpeedometerApp.TAG, "Unable to get auth cookie", e);
      } catch (IOException e) {
        Log.e(SpeedometerApp.TAG, "Unable to get auth cookie", e);
      }
    }
  }
  
  private synchronized boolean checkGetCookie() {
    if (isTestingServer()) {
      authCookie = getFakeAuthCookie();
      return true;
    }
    if (getCookieTask == null) {
      Log.i(SpeedometerApp.TAG, "checkGetCookie called too early");
      return false;
    }
    if (getCookieTask.getStatus() == AsyncTask.Status.FINISHED) {
      try {
        authCookie = getCookieTask.get();
        Log.i(SpeedometerApp.TAG, "Got authCookie: " + authCookie);
        return true;
      } catch (InterruptedException e) {
        Log.e(SpeedometerApp.TAG, "Unable to get auth cookie", e);
        return false;
      } catch (ExecutionException e) {
        Log.e(SpeedometerApp.TAG, "Unable to get auth cookie", e);
        return false;
      }
    } else {
      return false;
    }
  }
  
  private void sendStringMsg(String str) {
    UpdateIntent intent = new UpdateIntent(str);
    speedometer.sendBroadcast(intent);    
  }
}
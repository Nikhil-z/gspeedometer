// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.wireless.speed.speedometer.measurements;

import com.google.wireless.speed.speedometer.MeasurementDesc;
import com.google.wireless.speed.speedometer.MeasurementError;
import com.google.wireless.speed.speedometer.MeasurementResult;
import com.google.wireless.speed.speedometer.MeasurementTask;
import com.google.wireless.speed.speedometer.PhoneUtils;
import com.google.wireless.speed.speedometer.SpeedometerApp;
import com.google.wireless.speed.speedometer.util.MeasurementJsonConvertor;
import com.google.wireless.speed.speedometer.util.RuntimeUtil;
import com.google.wireless.speed.speedometer.util.Util;

import android.net.http.AndroidHttpClient;
import android.util.Log;
import android.util.Base64;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

/**
 * A Callable class that performs download throughput test using HTTP get
 * @author wenjiezeng@google.com (Steve Zeng)
 *
 */
public class HttpTask extends MeasurementTask {
  
  public static final String TYPE = "http";
  /* TODO(Wenjie): Depending on state machine configuration of cell tower's radio,
   * the size to find the 'real' bandwidth of the phone may be network dependent.  
   */
  // The maximum number of bytes we will read from requested URL. Set to 1Mb.
  public static final long MAX_HTTP_RESPONSE_SIZE = 1024 * 1024;
  // The buffer size we use to store the response body and report to the service. Set to 16Kb.
  // If the response is larger than 1Kb, we will only report the first 1Kb of the body.
  public static final int MAX_BODY_SIZE = 1024;
  // Not used by the HTTP protocol. Just in case we do not receive a status line from the response
  public static final int DEFAULT_STATUS_CODE = 0;
  // The default number of times we run a HTTP measurement
  private static final long DEFAULT_CNT = 1;

  public HttpTask(MeasurementDesc measurementDesc, SpeedometerApp parent) {
    super(measurementDesc, parent);
  }
  
  /**
   * The description of a HTTP measurement 
   */
  public static class HttpDesc extends MeasurementDesc {
    private String url;
    private String method;
    private String headers;
    private String body;

    public HttpDesc(String key, Date startTime, Date endTime,
                      double intervalSec, long count, long priority, Map<String, String> params) 
                      throws InvalidParameterException {
      super(HttpTask.TYPE, key, startTime, endTime, intervalSec, count, priority, params);
      initalizeParams(params);
      if (this.url == null) {
        throw new InvalidParameterException("URL for http task is null");
      }
    }
    
    @Override
    protected void initalizeParams(Map<String, String> params) {
      
      if (params == null) {
        return;
      }
      
      if (this.count == 0) {
        this.count = HttpTask.DEFAULT_CNT;
      }
      
      this.url = params.get("url");
      if (!this.url.startsWith("http://") && !this.url.startsWith("https://")) {
        this.url = "http://" + this.url;
      }
      if ((this.method = params.get("method")) == null) { 
        this.method = "get";
      }
      
      this.headers = params.get("headers");      
      this.body = params.get("body");
    }
    
    @Override
    public String getType() {
      return HttpTask.TYPE;
    }
    
  }
  /** Runs the HTTP measurement task. Will acquire power lock to ensure wifi is not turned off */
  @Override
  public MeasurementResult call() throws MeasurementError {
    int statusCode = HttpTask.DEFAULT_STATUS_CODE;
    long duration = 0;
    long originalHeadersLen = 0;
    long originalBodyLen;
    String headers = null;
    ByteBuffer body = ByteBuffer.allocate(HttpTask.MAX_BODY_SIZE);
    boolean success = false;
    String errorMsg = "";
    InputStream inputStream = null;
    
    try {
      /* Prevents the phone from going to low-power mode where WiFi turns off */
      PhoneUtils.getPhoneUtils().acquireWakeLock();
      // set the download URL, a URL that points to a file on the Internet
      // this is the file to be downloaded
      HttpDesc task = (HttpDesc) this.measurementDesc;
      String urlStr = task.url;
          
      HttpClient client = AndroidHttpClient.newInstance(Util.prepareUserAgent(this.parent));      
      HttpRequestBase request = null;
      if (task.method.compareToIgnoreCase("head") == 0) {
        request = new HttpHead(urlStr);
      } else if (task.method.compareToIgnoreCase("get") == 0) {
        request = new HttpGet(urlStr);
      } else if (task.method.compareToIgnoreCase("post") == 0) {
        request = new HttpPost(urlStr);
        HttpPost postRequest = (HttpPost) request;
        postRequest.setEntity(new StringEntity(task.body));
      } else {
        Log.e(SpeedometerApp.TAG, "Unsupported HTTP task");
      }
      
      if (task.headers != null && task.headers.trim().length() > 0) {
        for (String headerLine : task.headers.split("\r\n")) {
          String tokens[] = headerLine.split(":");
          if (tokens.length == 2) {
            request.addHeader(tokens[0], tokens[1]);
          } else {
            throw new MeasurementError("Incorrect header line: " + headerLine);
          }
        }
      }
      
      
      byte[] readBuffer = new byte[HttpTask.MAX_BODY_SIZE];
      int readLen;      
      int totalBodyLen = 0;
      
      long startTime = System.currentTimeMillis();
      HttpResponse response = client.execute(request);
      
      StatusLine statusLine = response.getStatusLine();
      if (statusLine != null) {
        statusCode = statusLine.getStatusCode();
        success = (statusCode == 200);
      }
      
      /* For HttpClient to work properly, we still want to consume the entire response even if
       * the status code is not 200 
       */
      HttpEntity responseEntity = response.getEntity();      
      originalBodyLen = responseEntity.getContentLength();
      
      if (responseEntity != null) {
        inputStream = responseEntity.getContent();
        int offset = 0;
        int lengthToRead = HttpTask.MAX_BODY_SIZE;
        int lastPos = 0;
        while ((readLen = inputStream.read(readBuffer)) > 0 
            && totalBodyLen <= HttpTask.MAX_HTTP_RESPONSE_SIZE) {
          totalBodyLen += readLen;
          // Fill in the body to report up to MAX_BODY_SIZE
          if (body.remaining() > 0) {
            int putLen = body.remaining() < readLen ? body.remaining() : readLen; 
            body.put(readBuffer, 0, putLen);
          }
        }
        duration = System.currentTimeMillis() - startTime;
      }
                 
      Header[] responseHeaders = response.getAllHeaders();
      if (responseHeaders != null) {
        headers = "";
        for (Header hdr : responseHeaders) {
          /*
           * TODO(Wenjie): There can be preceding and trailing whitespaces in
           * each header field. I cannot find internal methods that returns the
           * number of bytes in a header. The solution here assumes the encoding
           * is one byte per character.
           */
          originalHeadersLen += hdr.toString().length();
          headers += hdr.toString() + "\r\n";
        }
      }
      
      Log.d(SpeedometerApp.TAG, "HeaderInfo");
      Log.d(SpeedometerApp.TAG, headers);
      
      MeasurementResult result = new MeasurementResult(RuntimeUtil.getDeviceInfo().deviceId, 
        RuntimeUtil.getDeviceProperty(), HttpTask.TYPE, Calendar.getInstance().getTime(), 
        success, this.measurementDesc);
      
      result.addResult("code", statusCode);
      
      if (success) {
        result.addResult("time_ms", duration);
        result.addResult("headers_len", originalHeadersLen);
        result.addResult("body_len", originalBodyLen);
        result.addResult("headers", headers);
        result.addResult("body", Base64.encodeToString(body.array(), Base64.DEFAULT));
      }
      
      Log.i(SpeedometerApp.TAG, MeasurementJsonConvertor.toJsonString(result));
      return result;    
    } catch (MalformedURLException e) {
      errorMsg += e.getMessage() + "\n";
      Log.e(SpeedometerApp.TAG, e.getMessage());
    } catch (IOException e) {
      errorMsg += e.getMessage() + "\n";
      Log.e(SpeedometerApp.TAG, e.getMessage());
    } finally {
      PhoneUtils.getPhoneUtils().shutDown();
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          Log.e(SpeedometerApp.TAG, "Fails to close the input stream from the HTTP response");
        }
      }
    }
    throw new MeasurementError("Cannot get result from HTTP measurement because " + 
      errorMsg);
  }  

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return HttpDesc.class;
  }
  
  @Override
  public String getType() {
    return HttpTask.TYPE;
  }

}
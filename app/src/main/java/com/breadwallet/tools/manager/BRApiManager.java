package com.breadwallet.tools.manager;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;

import com.breadwallet.BreadApp;
import com.breadwallet.presenter.entities.CurrencyEntity;
import com.breadwallet.tools.sqlite.CurrencyDataSource;
import com.breadwallet.tools.threads.BRExecutor;

import com.breadwallet.tools.util.BRConstants;
import com.breadwallet.tools.util.Utils;
import com.platform.APIClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

import static com.breadwallet.tools.util.BRConstants.*;
import static com.breadwallet.tools.util.BRConstants.LW_API_HOST;
import static com.breadwallet.tools.util.BRConstants.LW_BACKUP_API_HOST;

public class BRApiManager {
    private static BRApiManager instance;
    private Timer timer;

    private TimerTask timerTask;

    private Handler handler;

    private BRApiManager() {
        handler = new Handler();
    }

    public static BRApiManager getInstance() {
        if (instance == null) {
            instance = new BRApiManager();
        }
        return instance;
    }

    private Set<CurrencyEntity> getCurrencies(Activity context) {
        Set<CurrencyEntity> set = new LinkedHashSet<>();
        try {
            JSONArray arr = fetchRates(context);
            updateFeePerKb(context);
            if (arr != null) {
                String selectedISO = BRSharedPrefs.getIso(context);
                int length = arr.length();
                for (int i = 0; i < length; i++) {
                    CurrencyEntity tmp = new CurrencyEntity();
                    try {
                        JSONObject tmpObj = (JSONObject) arr.get(i);
                        tmp.name = tmpObj.getString("code");
                        tmp.code = tmpObj.getString("code");
                        tmp.rate = (float) tmpObj.getDouble("n");
                        if (tmp.code.equalsIgnoreCase(selectedISO)) {
                            BRSharedPrefs.putIso(context, tmp.code);
                            BRSharedPrefs.putCurrencyListPosition(context, i - 1);
                        }
                    } catch (JSONException e) {
                        Timber.e(e);
                    }
                    set.add(tmp);
                }
            } else {
                Timber.d("timber: getCurrencies: failed to get currencies");
            }
        } catch (Exception e) {
            Timber.e(e);
        }
        List tempList = new ArrayList<>(set);
        Collections.reverse(tempList);
        return new LinkedHashSet<>(set);
    }


    private void initializeTimerTask(final Context context) {
        timerTask = new TimerTask() {
            public void run() {
                //use a handler to run a toast that shows the current timestamp
                handler.post(new Runnable() {
                    public void run() {
                        BRExecutor.getInstance().forLightWeightBackgroundTasks().execute(new Runnable() {
                            @Override
                            public void run() {
                                if (!BreadApp.isAppInBackground(context)) {
                                    Timber.d("timber: doInBackground: Stopping timer, no activity on.");
                                    BRApiManager.getInstance().stopTimerTask();
                                }
                                Set<CurrencyEntity> tmp = getCurrencies((Activity) context);
                                CurrencyDataSource.getInstance(context).putCurrencies(tmp);
                            }
                        });
                    }
                });
            }
        };
    }

    public void startTimer(Context context) {
        //set a new Timer
        if (timer != null) return;
        timer = new Timer();

        //initialize the TimerTask's job
        initializeTimerTask(context);

        //schedule the timer, after the first 0ms the TimerTask will run every 60000ms
        timer.schedule(timerTask, 0, 60000);
    }

    public void stopTimerTask() {
        //stop the timer, if it's not already null
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    public static JSONArray fetchRates(Activity activity) {
        String jsonString = createGETRequestURL(activity,  LW_API_HOST + "/api/v1/rates");
        JSONArray jsonArray = null;
        if (jsonString == null) return null;
        try {
            jsonArray = new JSONArray(jsonString);
        } catch (JSONException ex) {
            Timber.e(ex);
        }
        return jsonArray == null ? backupFetchRates(activity) : jsonArray;
    }

    public static JSONArray backupFetchRates(Activity activity) {
        String jsonString = createGETRequestURL(activity, LW_BACKUP_API_HOST + "/api/v1/rates");

        AnalyticsManager.logCustomEvent(_20230113_BAC);

        JSONArray jsonArray = null;
        if (jsonString == null) return null;
        try {
            jsonArray = new JSONArray(jsonString);

        } catch (JSONException e) {
            Timber.e(e);
        }
        return jsonArray;
    }

    public static void updateFeePerKb(Context app) {

        //Operationally, it makes more sense to review the fees than rely on a server.
        // Especially, when the missing server causes a major disconnect
        // Using this hard code for a bi-yearly review. This matches the architecture in iOS
        // KCW: May 3, 2022
        String jsonString = "{'fee_per_kb': 10000, 'fee_per_kb_economy': 2500, 'fee_per_kb_luxury': 66746}";
        try {
            JSONObject obj = new JSONObject(jsonString);
            // TODO: Refactor when mobile-api v0.4.0 is in prod
            long regularFee = obj.optLong("fee_per_kb");
            long economyFee = obj.optLong("fee_per_kb_economy");
            long luxuryFee = obj.optLong("fee_per_kb_luxury");
            FeeManager.getInstance().setFees(luxuryFee, regularFee, economyFee);
            BRSharedPrefs.putFeeTime(app, System.currentTimeMillis()); //store the time of the last successful fee fetch
        } catch (JSONException e) {
            Timber.e(new IllegalArgumentException("updateFeePerKb: FAILED: " + jsonString, e));
        }
    }

    // createGETRequestURL
    // Creates the params and headers to make a GET Request
    private static String createGETRequestURL(Context app, String myURL) {
        Request request = new Request.Builder()
                .url(myURL)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-agent", Utils.getAgentString(app, "android/HttpURLConnection"))
                .get().build();
        String response = null;
        Response resp = APIClient.getInstance(app).sendRequest(request, false, 0);

        try {
            if (resp == null) {
                Timber.i("timber: urlGET: %s resp is null", myURL);
                return null;
            }
            response = resp.body().string();
            String strDate = resp.header("date");
            if (strDate == null) {
                Timber.i("timber: urlGET: strDate is null!");
                return response;
            }
            SimpleDateFormat formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            Date date = formatter.parse(strDate);
            long timeStamp = date.getTime();
            BRSharedPrefs.putSecureTime(app, timeStamp);
        } catch (ParseException | IOException e) {
            Timber.e(e);
        } finally {
            if (resp != null) resp.close();
        }
        return response;
    }
}

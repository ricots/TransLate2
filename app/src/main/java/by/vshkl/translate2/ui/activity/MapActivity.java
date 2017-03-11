package by.vshkl.translate2.ui.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomSheetBehavior;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.arellomobile.mvp.MvpAppCompatActivity;
import com.arellomobile.mvp.presenter.InjectPresenter;
import com.arlib.floatingsearchview.FloatingSearchView;
import com.arlib.floatingsearchview.FloatingSearchView.OnQueryChangeListener;
import com.arlib.floatingsearchview.FloatingSearchView.OnSearchListener;
import com.arlib.floatingsearchview.suggestions.model.SearchSuggestion;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnMapClickListener;
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.util.HashMap;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import by.vshkl.translate2.App;
import by.vshkl.translate2.R;
import by.vshkl.translate2.mvp.model.MarkerWrapper;
import by.vshkl.translate2.mvp.model.Stop;
import by.vshkl.translate2.mvp.presenter.MapPresenter;
import by.vshkl.translate2.mvp.view.MapView;
import by.vshkl.translate2.util.CookieUtils;
import by.vshkl.translate2.util.DialogUtils;
import by.vshkl.translate2.util.Navigation;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.OnPermissionDenied;
import permissions.dispatcher.OnShowRationale;
import permissions.dispatcher.PermissionRequest;
import permissions.dispatcher.RuntimePermissions;

@RuntimePermissions
public class MapActivity extends MvpAppCompatActivity implements MapView, ConnectionCallbacks, OnMapReadyCallback,
        OnMapClickListener, OnMarkerClickListener, OnQueryChangeListener, OnSearchListener {

    private static final float ZOOM_CITY = 11F;
    private static final float ZOOM_OVERVIEW = 15F;
    private static final float ZOOM_POSITION = 16F;
    private static final double DEFAULT_LATITUDE = 53.9024429;
    private static final double DEFAULT_LONGITUDE = 27.5614649;
    private static final String URL_DASHBOARD = "http://www.minsktrans.by/lookout_yard/Home/Index/minsk?stopsearch&s=";

    @BindView(R.id.root) CoordinatorLayout clRoot;
    @BindView(R.id.sv_search) FloatingSearchView svSearch;
    @BindView(R.id.fl_bottom_sheet) FrameLayout flBottomSheet;
    @BindView(R.id.fb_location) FloatingActionButton fabLocation;
    @BindView(R.id.tv_stop_name) TextView tvStopName;
    @BindView(R.id.wv_dashboard) WebView wvDashboard;
    @BindView(R.id.pb_loading) ProgressBar pbLoading;

    @InjectPresenter MapPresenter presenter;
    private GoogleMap map;
    private GoogleApiClient googleApiClient;
    private HashMap<Integer, MarkerWrapper> visibleMarkers;
    private BottomSheetBehavior bottomSheetBehavior;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        ButterKnife.bind(this);
        initializeGoogleMap();
        initializeBottomSheet();
        initializeGoogleApiClient();
        initializeSearchView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        googleApiClient.connect();
    }

    @Override
    protected void onPause() {
        googleApiClient.disconnect();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.getRefWatcher(this).watch(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        MapActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    public void onBackPressed() {
        switch (bottomSheetBehavior.getState()) {
            case BottomSheetBehavior.STATE_EXPANDED:
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                break;
            default:
                super.onBackPressed();
        }
    }

    //------------------------------------------------------------------------------------------------------------------

    @OnClick(R.id.fb_location)
    void onLocationClicked() {
        MapActivityPermissionsDispatcher.updateCoordinatesWithCheck(this);
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        showUserLocation();
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        setupMap();
        presenter.getUpdatedTimestampFromRemoteDatabase();
        presenter.getAllStopsFromLocalDatabase();
    }

    @Override
    public void onMapClick(LatLng latLng) {
        dropMarkerHighlight(map.getCameraPosition().zoom);
        if (bottomSheetBehavior != null) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            fabLocation.show();
        }
    }

    @Override
    public boolean onMarkerClick(Marker marker) {
        MarkerWrapper markerWrapper = new MarkerWrapper(marker);
        if (visibleMarkers.containsValue(markerWrapper)) {
            visibleMarkers.keySet().stream()
                    .filter(key -> visibleMarkers.get(key).equals(markerWrapper))
                    .forEach(key -> {
                        presenter.getStopById(key);
                        highlightSelectedMarker(key);
                    });
        }
        return false;
    }

    @Override
    public void onSearchTextChanged(String oldQuery, String newQuery) {
        if (!oldQuery.equals("") && newQuery.equals("")) {
            svSearch.clearSuggestions();
        }
        if (newQuery.length() > 2) {
            presenter.searchStops(newQuery);
        }
    }

    @Override
    public void onSuggestionClicked(SearchSuggestion searchSuggestion) {
        Stop stop = (Stop) searchSuggestion;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(stop.getLatitude(), stop.getLongitude()), ZOOM_POSITION));
    }

    @Override
    public void onSearchAction(String currentQuery) {

    }

    //------------------------------------------------------------------------------------------------------------------

    @Override
    public void showUpdateStopsSnackbar() {
        Snackbar.make(clRoot, R.string.map_update_stops_message, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.map_update_stops_update, view -> presenter.getAllStopsFromRemoteDatabase())
                .show();
    }

    @Override
    public void initializeMap() {
        initializeGoogleMap();
    }

    @Override
    public void showMessage(int messageId) {
        Snackbar.make(clRoot, messageId, Snackbar.LENGTH_SHORT).show();
    }

    @SuppressLint("UseSparseArrays")
    @Override
    public void placeMarkers(final List<Stop> stopList) {
        if (map != null) {
            visibleMarkers = new HashMap<>();
            map.setOnCameraMoveListener(() -> addItemsToMap(stopList, map.getCameraPosition().zoom));
        }
    }

    @Override
    public void showSelectedStop(final Stop stop) {
        tvStopName.setText(stop.getName());
        loadWebView(URL_DASHBOARD + stop.getId());
        fabLocation.hide(new FloatingActionButton.OnVisibilityChangedListener() {
            @Override
            public void onHidden(FloatingActionButton fab) {
                super.onHidden(fab);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        });
    }

    @Override
    public void showSearchResult(List<Stop> stopList) {
        svSearch.swapSuggestions(stopList);
    }

    @Override
    public void showProgressBar() {
        pbLoading.setVisibility(View.VISIBLE);
    }

    @Override
    public void hideProgressBar() {
        pbLoading.setVisibility(View.GONE);
    }

    //------------------------------------------------------------------------------------------------------------------

    @NeedsPermission({Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION})
    void updateCoordinates() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean hasProvider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                || lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (hasProvider) {
            showUserLocation();
        } else {
            DialogUtils.showLocationTurnOnDialog(this);
        }
    }

    @OnShowRationale({Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION})
    void showRationaleForLocation(final PermissionRequest request) {
        DialogUtils.showLocationRationaleDialog(this, request);
    }

    @OnPermissionDenied({Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION})
    void onDeniedForLocation() {
        Snackbar.make(clRoot, R.string.map_permission_denied_message, Snackbar.LENGTH_LONG)
                .setAction(R.string.map_permission_denied_settings, view -> Navigation.navigateToAppSettings(this))
                .show();
    }

    //------------------------------------------------------------------------------------------------------------------

    public static Intent newIntent(Context context) {
        return new Intent(context, MapActivity.class);
    }

    private void initializeGoogleMap() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.fr_map);
        mapFragment.getMapAsync(this);
    }

    private void initializeGoogleApiClient() {
        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addApi(LocationServices.API).build();
        }
    }

    private void initializeBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(flBottomSheet);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        bottomSheetBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    fabLocation.show();
                    dropMarkerHighlight(map.getCameraPosition().zoom);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {

            }
        });
        initializeWebView();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initializeWebView() {
        WebSettings settings = wvDashboard.getSettings();
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAppCacheEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NARROW_COLUMNS);
        settings.setJavaScriptEnabled(true);
        wvDashboard.setOnTouchListener((v, event) -> true);
        wvDashboard.setWebViewClient(new WebViewClient());
    }

    private void initializeSearchView() {
        svSearch.setOnQueryChangeListener(this);
        svSearch.setOnSearchListener(this);
    }

    private void setupMap() {
        UiSettings settings = map.getUiSettings();
        settings.setCompassEnabled(false);
        settings.setMyLocationButtonEnabled(false);
        settings.setMapToolbarEnabled(false);
        map.setBuildingsEnabled(true);
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE), ZOOM_CITY));
        map.setOnMapClickListener(this);
        map.setOnMarkerClickListener(this);
    }

    private void showUserLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Location location = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
        if (location != null) {
            LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
            map.setMyLocationEnabled(true);
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, ZOOM_POSITION));
        }
    }

    private void loadWebView(final String url) {
        CookieManager.getInstance().setCookie("", CookieUtils.getCookies(getApplicationContext()));
        wvDashboard.clearHistory();
        wvDashboard.loadUrl(url);
    }

    private void addItemsToMap(final List<Stop> stopList, float zoom) {
        LatLngBounds latLngBounds = map.getProjection().getVisibleRegion().latLngBounds;
        for (Stop stop : stopList) {
            int stopId = stop.getId();
            LatLng latLng = new LatLng(stop.getLatitude(), stop.getLongitude());
            if (zoom >= ZOOM_OVERVIEW) {
                if (latLngBounds.contains(latLng)) {
                    if (!visibleMarkers.containsKey(stopId)) {
                        MarkerWrapper marker = new MarkerWrapper(map.addMarker(new MarkerOptions()
                                .position(latLng).icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_place))));
                        visibleMarkers.put(stopId, marker);
                    }
                } else if (visibleMarkers.containsKey(stopId) && !visibleMarkers.get(stopId).isSelected()) {
                    visibleMarkers.get(stopId).getMarker().remove();
                    visibleMarkers.remove(stopId);
                }
            } else if (visibleMarkers.containsKey(stopId) && !visibleMarkers.get(stopId).isSelected()) {
                visibleMarkers.get(stopId).getMarker().remove();
                visibleMarkers.remove(stopId);
            }
        }
    }

    private void highlightSelectedMarker(Integer key) {
        float zoom = map.getCameraPosition().zoom;
        if (zoom < ZOOM_OVERVIEW) {
            return;
        }
        dropMarkerHighlight(zoom);
        visibleMarkers.get(key).setSelected(true);
        visibleMarkers.get(key).setIcon(BitmapDescriptorFactory.fromResource(R.drawable.ic_place_selected));
    }

    private void dropMarkerHighlight(float zoom) {
        if (zoom < ZOOM_OVERVIEW) {
            for (Integer key : visibleMarkers.keySet()) {
                visibleMarkers.get(key).getMarker().remove();
                visibleMarkers.remove(key);
            }
        }
        visibleMarkers.keySet().stream()
                .filter(key -> visibleMarkers.get(key).isSelected())
                .forEach(key -> {
                    visibleMarkers.get(key).setSelected(false);
                    visibleMarkers.get(key).setIcon(BitmapDescriptorFactory.fromResource(R.drawable.ic_place));
                });
    }
}

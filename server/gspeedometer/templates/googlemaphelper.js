<script src="http://maps.google.com/maps?file=api&amp;v=2&amp;key={{googlemap_key}}" type="text/javascript"></script>
<script type="text/javascript">
function load() {
  if (GBrowserIsCompatible()) {
    function point(lat, lon, html, icon) {
      this.gpoint = new GMarker(new GLatLng(lat, lon), icon);
      this.html = html;
    }

    function map(id, points, lat, lon, zoom) {
      this.id = id;
      this.points = points;
      this.gmap = new GMap2(document.getElementById(this.id));
      this.gmap.setCenter(new GLatLng(lat, lon), zoom);
      this.addMarker = addMarker;
      this.addMarkers = addMarkers;
      this.makePointsFromArray = makePointsFromArray;

      function addMarkers(array) {
        for (var i in array) {
          this.addMarker(array[i]);
        }
      }

      function makePointsFromArray(map_points) {
        for (var i in map_points) {
          points[i] = new point(map_points[i][0],
              map_points[i][1],
              map_points[i][2],
              map_points[i][3]);
        }
        return points;
      }

      function addMarker(point) {
        if (point.html) {
          GEvent.addListener(point.gpoint, "click",
              function() {
                point.gpoint.openInfoWindowHtml(point.html);
              });
        }
        this.gmap.addOverlay(point.gpoint);
      }

      this.points = makePointsFromArray(this.points);
      this.addMarkers(this.points);
    }

    {% for icon in icons %}
    var {{icon.icon_id}} = new GIcon();
    {{icon.icon_id}}.image = '{{icon.image}}';
    {{icon.icon_id}}.shadow = '{{icon.shadow}}';
    {{icon.icon_id}}.iconSize = new GSize{{icon.icon_size}};
    {{icon.icon_id}}.shadowSize = new GSize{{icon.shadow_size}};
    {{icon.icon_id}}.iconAnchor = new GPoint{{icon.icon_anchor}};
    {{icon.icon_id}}.infoWindowAnchor = 
        new GPoint{{icon.info_window_anchor}};
    {% endfor %}

    map_points = {{points|safe}}

    var {{map.map_id}} = new map('{{map.map_id}}', map_points,
        {{center_lat}}, {{center_lon}}, {{map.zoom}});

    {% if map.show_navcontrols %}
    {{map.map_id}}.gmap.addControl(new GSmallMapControl());
    {% endif %}

    {% if map.show_mapcontrols %}
    {{map.map_id}}.gmap.addControl(new GMapTypeControl());
    {% endif %}
  }
}
</script>


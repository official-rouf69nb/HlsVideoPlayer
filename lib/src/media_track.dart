
///Available video track that can be played.
///It is required to change quality
class MediaTrack {
  int height;
  int width;
  int bitrate;
  int rendererIndex;
  int groupIndex;
  int trackIndex;
  bool selected;

  MediaTrack(
      {this.height,
        this.width,
        this.bitrate,
        this.rendererIndex,
        this.groupIndex,
        this.trackIndex,
        this.selected});

  MediaTrack.fromJson(Map<String, dynamic> json) {
    height = json['height'];
    width = json['width'];
    bitrate = json['bitrate'];
    rendererIndex = json['rendererIndex'];
    groupIndex = json['groupIndex'];
    trackIndex = json['trackIndex'];
    selected = json['selected'];
  }

  static List<MediaTrack> listFromJson(List<dynamic> json){
    List<MediaTrack> _list=[];

    if(json != null){
      json.forEach((element) {
        _list.add(MediaTrack.fromJson(element));
      });
    }

    return _list;
  }
}
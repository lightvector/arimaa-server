'use strict';

var gulp = require('gulp');
var sass = require('gulp-sass');
var jade = require('gulp-jade');
var watchify = require('watchify');
var browserify = require('browserify');
var reactify = require('reactify');
var streamify = require('gulp-streamify');
var uglify = require('gulp-uglify');
var gutil = require('gulp-util');
var source = require('vinyl-source-stream');
var minifer = require('gulp-uglify/minifier');
var uglifyjs = require('uglify-js');
var es = require('event-stream');
var rename = require('gulp-rename');

var path = {
  JS: './frontend/js/**/*.js',
  JS_PATH: './frontend/js/',
  ENTRIES: ['app.js','chat.js'],
  ENTRY_APP: 'app.js',
  ENTRY_CHAT: 'chat.js',

  SCSS:'frontend/scss/*.scss',
  SCSS_PATH:'frontend/scss',
  CSS_OUT:'src/main/webapp/style',
  JADE_WATCH: 'frontend/jade/**/*.jade',  //watch to see if layouts change
  JADE_VIEWS: 'frontend/jade/views/*.jade', //but only compile views so we won't create empty layout htmls
  HTML_OUT:'src/main/webapp',

  DEST_BUILD:'src/main/webapp/js'
}

function onError(e) {
  var s = "";
  s += "file: " + e.fileName + "\n";
  s += "descr: " + e.description + "\n";
  s += "line: "  + e.lineNumber + "\n";
  console.log(s);
  if(!e.description) {
    console.log(e);
  }
  this.emit("end");
}

function onScssError(e) {
  sass.logError(e);
  this.emit("end");
}

//based on https://gist.github.com/Sigmus/9253068
function buildScript(filename, watch) {
  var props = {entries: [path.JS_PATH + filename],cache: {}, packageCache: {}, debug:true};
  var bundler = watch ? watchify(browserify(props)) : browserify(props);
  bundler.transform(reactify);
  function rebundle() {
    var stream = bundler.bundle();

    //there might be a way to pipe conditionally
    if(process.env.NODE_ENV === 'production') {
      return stream.on('error', onError)
      .pipe(source(filename))
      .pipe(streamify(minifer({}, uglifyjs).on('error', gutil.log)))
      .pipe(rename({
            extname: '.min.js'
      }))
      .pipe(gulp.dest(path.DEST_BUILD));
    } else {
      return stream.on('error', onError)
      .pipe(source(filename))
      .pipe(gulp.dest(path.DEST_BUILD));
    }
  }
  bundler.on('update', function() {
    rebundle();
    console.log('update');
  });
  return rebundle();
}

gulp.task('scss', function() {
  return gulp.src(path.SCSS)
    .pipe(sass().on('error', sass.logError))
    //.pipe( csso() ) //minimizer
    .pipe(gulp.dest(path.CSS_OUT));
});

gulp.task('jade', function() {
  return gulp.src(path.JADE_VIEWS)
    .pipe(jade({
      pretty: true,
      locals: {env:process.env.NODE_ENV}
    }))
    .pipe(gulp.dest(path.HTML_OUT));
});

//REMOVE
gulp.task("js", function() {
  var tasks = path.ENTRIES.map(function(entry) {
      return browserify({
        entries: [path.JS_PATH + entry],
        transform: [reactify],
      })
        .bundle().on('error', onError)
        .pipe(source(entry))
        .pipe(gulp.dest(path.DEST_BUILD)  );
      });
  return es.merge.apply(null, tasks);
});

gulp.task("js-app", function() {
  return buildScript(path.ENTRY_APP, false);
});

gulp.task("js-chat", function() {
  return buildScript(path.ENTRY_CHAT, false);
});

gulp.task("watch-app", function() {
  return buildScript(path.ENTRY_APP, true);
});

gulp.task("watch-chat", function() {
  return buildScript(path.ENTRY_CHAT, true);
});

gulp.task("set-prod-env", function() {
  return process.env.NODE_ENV = 'production';
});

gulp.task('watch-all', ['js-app', 'jade', 'scss'], function() {
  gulp.watch(path.JS, ['js-app']);
  gulp.watch(path.SCSS, ['scss']);
  gulp.watch(path.JADE_WATCH, ['jade']);
});

gulp.task('build', ['set-prod-env','js-app','jade','scss'], function(){
  console.log("build done");
});

gulp.task('default', ['watch-all']);

<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Laputa Template</title>
  <link href="/webjars/bootstrap/3.3.7/css/bootstrap.min.css" rel="stylesheet">
  <link href="/css/app.css" rel="stylesheet">
</head>
<body>
<div id="main-content" class="container">
  <div class="navbar-header">
    <button type="button" class="navbar-toggle" data-toggle="collapse" data-target=".navbar-collapse">
      <span class="sr-only">Toggle navigation</span>
      <span class="icon-bar"></span>
      <span class="icon-bar"></span>
      <span class="icon-bar"></span>
    </button>
    <a class="navbar-brand" href="/">Laputa Template</a>
  </div>

  <div class="row">
    <div class="col-lg-12 chart-canvas" id="canvas">
      <ul>
      <#if username??>
        <li>Logged in as ${username}</li>
        <li><a href="/logout.html">logout.html</a></li>
      <#else>
        <li><a href="/login.html">login.html</a></li>
      </#if>
        <li><a href="/product/sku.html">product/sku.html (login required)</a></li>
      </ul>
    </div>
  </div>
</div>

<script src="/webjars/jquery/3.1.1/jquery.min.js"></script>
<script src="/webjars/sockjs-client/1.1.1/sockjs.min.js"></script>
<script src="/webjars/stomp-websocket/2.3.3/stomp.min.js"></script>
<script src="/js/underscore-min.js"></script>
<script src="/js/app.js"></script>
</body>
</html>
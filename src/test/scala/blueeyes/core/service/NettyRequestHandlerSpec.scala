package blueeyes.core.service

import org.scalatest.mock.MockitoSugar
import org.specs.Specification
import org.jboss.netty.handler.codec.http.{HttpMethod => NettyHttpMethod, HttpVersion => NettyHttpVersion, HttpResponse => NettyHttpResponse}
import org.jboss.netty.handler.codec.http.DefaultHttpRequest
import org.jboss.netty.channel._
import org.jboss.netty.util.CharsetUtil
import org.mockito.Mockito.{when, times}
import org.mockito.{Matchers, Mockito, ArgumentMatcher}
import blueeyes.util.Future
import blueeyes.core.service.RestPathPatternImplicits._
import blueeyes.core.data.TextToTextBijection
import blueeyes.core.http.MimeTypes._
import blueeyes.core.http.{HttpStatusCodes, HttpVersions}

class NettyRequestHandlerSpec extends Specification with MockitoSugar {
  private val handler       = mock[HttpRequest[String] => Future[HttpResponse[String]]]
  private val context       = mock[ChannelHandlerContext]
  private val channel       = mock[Channel]
  private val channelFuture = mock[ChannelFuture]

  private implicit val transcoder = new HttpStringDataTranscoder(TextToTextBijection, text / html)
  
  private val response     = HttpResponse[String](HttpStatus(HttpStatusCodes.OK), Map("retry-after" -> "1"), Some("12"), HttpVersions.`HTTP/1.1`)
  private val hierarchy: RestHierarchy  = new TestService()
  private val nettyHandler = new NettyRequestHandler(hierarchy)

  "write OK responce service when path is match" in {
    val event  = mock[MessageEvent]
    val nettyRequest = new DefaultHttpRequest(NettyHttpVersion.HTTP_1_0, NettyHttpMethod.GET, "/bar/1/adCode.html")
    val future       = new Future[HttpResponse[String]]().deliver(response)

    when(event.getMessage()).thenReturn(nettyRequest, nettyRequest)
    when(handler.apply(Converters.fromNettyRequest(nettyRequest, Map('adId -> "1"), transcoder))).thenReturn(future, future)
    when(event.getChannel()).thenReturn(channel, channel)
    when(channel.write(Matchers.argThat(new RequestMatcher(Converters.toNettyResponse(response, transcoder))))).thenReturn(channelFuture, channelFuture)

    nettyHandler.messageReceived(context, event)

    Mockito.verify(channelFuture, times(1)).addListener(ChannelFutureListener.CLOSE)

    assert(true)
  }

  "write Not Found responce service when path is not match" in {
    val event        = mock[MessageEvent]
    val nettyRequest = new DefaultHttpRequest(NettyHttpVersion.HTTP_1_0, NettyHttpMethod.GET, "/foo/1/adCode.html")

    when(event.getMessage()).thenReturn(nettyRequest, nettyRequest)
    when(event.getChannel()).thenReturn(channel, channel)
    when(channel.write( Matchers.argThat(new RequestMatcher(Converters.toNettyResponse(HttpResponse[String](HttpStatus(HttpStatusCodes.NotFound)), transcoder))))).thenReturn(channelFuture, channelFuture)

    nettyHandler.messageReceived(context, event)

    Mockito.verify(channelFuture, times(1)).addListener(ChannelFutureListener.CLOSE)

    assert(true)    
  }

  class TestService extends RestHierarchyBuilder{
    path("bar" / 'adId / "adCode.html"){get[String, String](handler)}
  }

  class RequestMatcher(matchingResponce: NettyHttpResponse) extends ArgumentMatcher[NettyHttpResponse] {
     def matches(arg: Object ): Boolean = {
       val repsonce = arg.asInstanceOf[NettyHttpResponse]
       matchingResponce.getStatus == repsonce.getStatus && matchingResponce.getContent.toString(CharsetUtil.UTF_8) == repsonce.getContent.toString(CharsetUtil.UTF_8)
     }
  }
}

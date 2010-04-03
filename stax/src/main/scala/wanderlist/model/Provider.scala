package wanderlist.model 
import net.liftweb._ 
import mapper._ 
import http._ 
import SHtml._ 
import util._ 
import wanderlist.model._
import _root_.net.liftweb.http._ 
import S._ 
import _root_.net.liftweb.util._ 
import Helpers._
import _root_.scala.xml.{Group => XMLGroup, _}
import dispatch._
import oauth._
import OAuth._
import java.util.Date
import java.text.SimpleDateFormat

class GoogleProvider extends LongKeyedMapper[GoogleProvider] with IdPK { 
    def getSingleton = GoogleProvider 
    object authenticated extends MappedBoolean(this) 
    object owner extends MappedLongForeignKey(this, User)
    object accessTokenKey extends MappedString(this, 256)
    object accessTokenSecret extends MappedString(this, 256)
  
    val GetRequestToken = "OAuthGetRequestToken"
    val AuthorizeToken  = "OAuthAuthorizeToken"
    val GetAccessToken  = "OAuthGetAccessToken"
    val account = :/("www.google.com").secure / "accounts"
    val m8 = :/("www.google.com").secure / "m8" / "feeds"
    val contacts = m8 / "contacts"
    val groups = m8 / "groups"

    val h = new Http
    val consumer = Consumer(Props.get("googleConsumer.key").open_!, Props.get("googleConsumer.secret").open_!)

    def initiateRequest() = {
        var requestToken: Token = null
        val Callback: String = SHtml.link(Props.get("host").open_! + "google_callback", () => {
                          val verifier = java.net.URLDecoder.decode(S.param("oauth_verifier").open_!, "UTF-8")
                          val accessToken =  h(account / GetAccessToken <@ (consumer, requestToken, verifier) as_token)    
                          GoogleProvider.create.authenticated(true).owner(User.currentUser.open_!).accessTokenKey(accessToken.value).accessTokenSecret(accessToken.secret).save                            

                          getGoogleGroups()
                          getGoogleContacts()

                          S.redirectTo(Props.get("host").open_!)
                      }, Text("")).attribute("href").get.text
        val extras = Map("scope" -> m8.to_uri.toString, "google_callback" -> Callback)
        requestToken = h(account / GetRequestToken << extras <@ consumer as_token)
        val url = (account / AuthorizeToken <<? requestToken to_uri).toString
        S.redirectTo(url)   
    }

    def getTokenForUser(user: User) = {
        val cp = GoogleProvider.findAll(By(GoogleProvider.owner, user)).head
        Token(cp.accessTokenKey, cp.accessTokenSecret)
    }

    def parseDate(dateString: String) = {
        val parser =  new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'")
        parser.parse(dateString)
    }

    def getGoogleGroups() = {
        def parseAndStoreGroups(feed: scala.xml.Elem) =
            for (entry <- (feed \\ "entry")) {
                val name = (entry \ "title").text
                val lastUpdated = parseDate((entry \ "updated").text)
                var googleId = (entry \ "id").text
                Group.create.name(name).owner(User.currentUser.open_!).googleId(googleId).lastUpdated(lastUpdated).save
            }
        val accessToken = getTokenForUser(User.currentUser.open_!)
        h(groups / "default" / "full" <<? Map("max-results" -> 10000) <@ (consumer, accessToken) <> parseAndStoreGroups)
    }

    def getGoogleContacts() = {
      def parseAndStoreContacts(feed: scala.xml.Elem) = {
          for (entry <- (feed \\ "entry")) {
              val name = (entry \ "title").text
              val lastUpdated = parseDate((entry \ "updated").text)
              var googleId = (entry \ "id").text
              val newContact = Contact.create.name(name).owner(User.currentUser.open_!).googleId(googleId).lastUpdated(lastUpdated)
              newContact.save  
              for (email <- (entry \\ "email")) {
                ContactEmail.create.email((email \ "@address").toString).contact(newContact).save
              }
              for (googleGroup <- (entry \\ "groupMembershipInfo")) {
                //println((group \ "@href").toString)
                val group = Group.findAll(By(Group.googleId, (googleGroup \ "@href").toString)).head
                ContactGroup.join(newContact, group)
              }
          }
      }
      val accessToken = getTokenForUser(User.currentUser.open_!)
      h(contacts / "default" / "full" <<? Map("max-results" -> 10000) <@ (consumer, accessToken) <> parseAndStoreContacts)
    }

    def extractId(feed: scala.xml.Elem): String =
    (feed \ "id").text
    def getUserId(token: Token) = h(contacts / "default" / "full" <<? Map("max-results" -> 0) <@ (consumer, token) <> extractId)
    //def getTenKContacts(token: Token) = h(contacts / "default" / "full" <<? Map("max-results" -> 10000) <@ (consumer, token) <> parse)
}
object GoogleProvider extends GoogleProvider with LongKeyedMetaMapper[GoogleProvider] {}

package messengerbot;

import com.github.messenger4j.common.WebviewHeightRatio;
import com.github.messenger4j.send.buttons.Button;
import com.github.messenger4j.send.buttons.UrlButton;
import com.github.messenger4j.send.templates.GenericTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import com.github.messenger4j.MessengerPlatform;
import com.github.messenger4j.exceptions.MessengerApiException;
import com.github.messenger4j.exceptions.MessengerIOException;
import com.github.messenger4j.exceptions.MessengerVerificationException;
import com.github.messenger4j.receive.MessengerReceiveClient;
import com.github.messenger4j.receive.events.AccountLinkingEvent.AccountLinkingStatus;
import com.github.messenger4j.receive.events.AttachmentMessageEvent.Attachment;
import com.github.messenger4j.receive.events.AttachmentMessageEvent.AttachmentType;
import com.github.messenger4j.receive.events.AttachmentMessageEvent.Payload;
import com.github.messenger4j.receive.handlers.*;
import com.github.messenger4j.send.*;
import com.github.messenger4j.send.templates.ButtonTemplate;
import com.mashape.unirest.http.exceptions.UnirestException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.awt.*;
import java.util.Date;
import java.util.List;


import static com.github.messenger4j.MessengerPlatform.*;

import types.*;

/**
 * This is the main class for inbound and outbound communication with the Facebook Messenger Platform.
 * The callback handler is responsible for the webhook verification and processing of the inbound messages and events.
 */
@RestController
@RequestMapping("/callback")
public class MessengerPlatformCallbackHandler {

    private static final Logger logger = LoggerFactory.getLogger(MessengerPlatformCallbackHandler.class);

    private final MessengerReceiveClient receiveClient;
    private final MessengerSendClient sendClient;
    private RequestHandler requestHandler;

    /**
     * Constructs the {@code MessengerPlatformCallbackHandler} and initializes the {@code MessengerReceiveClient}.
     *
     * @param appSecret   the {@code Application Secret}
     * @param verifyToken the {@code Verification Token} that has been provided by you during the setup of the {@code
     *                    Webhook}
     * @param sendClient  the initialized {@code MessengerSendClient}
     */
    @Autowired
    public MessengerPlatformCallbackHandler(@Value("${messenger4j.appSecret}") final String appSecret,
            @Value("${messenger4j.verifyToken}") final String verifyToken, final MessengerSendClient sendClient) {

        logger.debug("Initializing MessengerReceiveClient - appSecret: {} | verifyToken: {}", appSecret, verifyToken);
        requestHandler = new RequestHandler();
        this.receiveClient = MessengerPlatform.newReceiveClientBuilder(appSecret, verifyToken)
                .onTextMessageEvent(newTextMessageEventHandler())
                .onAttachmentMessageEvent(newAttachmentMessageEventHandler())
                .onQuickReplyMessageEvent(newQuickReplyMessageEventHandler()).onPostbackEvent(newPostbackEventHandler())
                .onAccountLinkingEvent(newAccountLinkingEventHandler()).onOptInEvent(newOptInEventHandler())
                .onEchoMessageEvent(newEchoMessageEventHandler())
                .onMessageDeliveredEvent(newMessageDeliveredEventHandler())
                .onMessageReadEvent(newMessageReadEventHandler()).fallbackEventHandler(newFallbackEventHandler())
                .build();
        this.sendClient = sendClient;
        requestHandler = new RequestHandler();
    }

    /**
     * Webhook verification endpoint.
     *
     * The passed verification token (as query parameter) must match the configured verification token.
     * In case this is true, the passed challenge string must be returned by this endpoint.
     */
    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> verifyWebhook(@RequestParam(MODE_REQUEST_PARAM_NAME) final String mode,
            @RequestParam(VERIFY_TOKEN_REQUEST_PARAM_NAME) final String verifyToken,
            @RequestParam(CHALLENGE_REQUEST_PARAM_NAME) final String challenge) {

        logger.debug("Received Webhook verification request - mode: {} | verifyToken: {} | challenge: {}", mode,
                verifyToken, challenge);
        try {
            return ResponseEntity.ok(this.receiveClient.verifyWebhook(mode, verifyToken, challenge));
        } catch (MessengerVerificationException e) {
            logger.warn("Webhook verification failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    /**
     * Callback endpoint responsible for processing the inbound messages and events.
     */
    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<Void> handleCallback(@RequestBody final String payload,
            @RequestHeader(SIGNATURE_HEADER_NAME) final String signature) {

        logger.debug("Received Messenger Platform callback - payload: {} | signature: {}", payload, signature);
        try {
            this.receiveClient.processCallbackPayload(payload, signature);
            logger.debug("Processed callback payload successfully");
            return ResponseEntity.status(HttpStatus.OK).build();
        } catch (MessengerVerificationException e) {
            logger.warn("Processing of callback payload failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    /**
     * Automated job to send messages out to subs
     */
    @Scheduled(cron = "*/60 * * * * *")
    private void sendAutomatedMessages()throws MessengerApiException, MessengerIOException {
        try {
            List<Subscriber> subs = requestHandler.GetSubscribers();

            subs.forEach(sub -> {
                try{
                    final ThemeTip themeTip = requestHandler.GetRandomThemeTipFacebookId(sub.getFacebook_id());
                    final  InteractionTip interactionTip = requestHandler.GetRandomIteractionTip();
                    sendBuildTip(sub.getFacebook_id(),themeTip,interactionTip);
                }
                catch (Exception e){

                }

            });
        } catch (Exception e) {
            logger.info("Failed to send out automated messages, retrying in 60s...");
        }
    }

    private TextMessageEventHandler newTextMessageEventHandler() {
        return event -> {
            logger.debug("Received TextMessageEvent: {}", event);

            final String messageId = event.getMid();
            final String messageText = event.getText();
            final String senderId = event.getSender().getId();
            final Date timestamp = event.getTimestamp();

            logger.info("Received message '{}' with text '{}' from user '{}' at '{}'", messageId, messageText, senderId,
                    timestamp);

            try {
                switch (messageText.toLowerCase()) {


                    case "gif":
                        sendGifMessage(senderId);
                        break;
                    case "tip":
                        sendTip(senderId);
                        break;
                    case "help":
                        sendHelp(senderId);
                        break;
                    case "aboneer":
                       subcribeUser(senderId);

                        break;
                    case "ben ik al geregistreerd":
                        checkUserStatus(senderId);
                        break;
                    case "check status":
                        checkUserStatus(senderId);
                        break;
                    case "uitschrijven":
                        unSub(senderId);
                        break;
                    default:

                        sendDefaultMessage(senderId);


                }
            } catch (MessengerApiException | MessengerIOException e) {
                handleSendException(e);
            }
        };
    }
    private void sendTip(String senderId)throws MessengerApiException, MessengerIOException{
        try{
            final ThemeTip themeTip = requestHandler.GetRandomThemeTip();
            final  InteractionTip interactionTip = requestHandler.GetRandomIteractionTip();
            sendBuildTip(senderId,themeTip, interactionTip);


        }catch (Exception e){

        }

    }
    private void  sendDefaultMessage(String senderId)throws MessengerApiException, MessengerIOException{
        try{
            sendTextMessage(senderId, "Hallo");
            if (requestHandler.UserIsSub(senderId,requestHandler.GetSubscribers())){
                sendTextMessage(senderId, "Hoe kan ik u verder helpen?");
                sendHelpSub(senderId);

            }else{
                sendRegistrationMessage(senderId);
            }


        }catch (Exception e){

        }

    }
    private void sendBuildTip(String senderId,ThemeTip themeTip,InteractionTip interactionTip)throws MessengerApiException, MessengerIOException{
        String themeTipImgUrl = "https://api-toddlr.herokuapp.com/images/"+themeTip.getPicture();

        final List<com.github.messenger4j.send.buttons.Button> buttonsTheme = Button.newListBuilder()
                .addUrlButton("Beoordeel tip", "https://docs.google.com/forms/d/e/1FAIpQLSekeCPYI_OxUHBOnRPorjyY6BXlMACZmXz2S2OiEYhQIxUSXw/viewform").toList()
                .addUrlButton("Bekijk tip", "https://scrummyb3ars.github.io/web-platform/#/tips/theme/"+themeTip.getId()).toList()
                .build();
        final List<com.github.messenger4j.send.buttons.Button> buttonsInteraction = Button.newListBuilder()
                .addUrlButton("Beoordeel tip", "https://docs.google.com/forms/d/e/1FAIpQLSekeCPYI_OxUHBOnRPorjyY6BXlMACZmXz2S2OiEYhQIxUSXw/viewform").toList()
                .addUrlButton("Bekijk tip", "https://scrummyb3ars.github.io/web-platform/#/tips/interaction/"+interactionTip.getId()).toList()
                .build();

        final GenericTemplate genericTemplate = GenericTemplate.newBuilder()
                .addElements()

                .addElement(themeTip.getTip_content().substring(0,77)+ "...")


                .subtitle(themeTip.getDevelopment_goal().substring(0,77)+ "...")

                .imageUrl(themeTipImgUrl)


                .buttons(buttonsTheme)
                .toList()

                .addElement(interactionTip.getTip_content().substring(0,77)+ "...")
                .imageUrl("https://www.onlineseminar.nl/media/1244/ols-tip-1.png")
                .buttons(buttonsInteraction)
                .toList()
                .done()
                .build();
        this.sendClient.sendTemplate(senderId, genericTemplate);
    }
    private void sendHelp(String recipientId) throws MessengerApiException, MessengerIOException {

        try {

            if (requestHandler.UserIsSub(recipientId, requestHandler.GetSubscribers())) {
                sendHelpSub(recipientId);

            } else {
                sendHelpUnSub(recipientId);
            }

        } catch (Exception e) {

        }
    }

    private void sendHelpSub(String recipientId) throws MessengerApiException, MessengerIOException {
        final List<QuickReply> quickReplies = QuickReply.newListBuilder()
                .addTextQuickReply("Check status","checkstatus").toList()
                .addTextQuickReply("Uitschrijven","uitschrijven").toList()
                .addTextQuickReply("Tip","tip").toList()

                .build();

        this.sendClient.sendTextMessage(recipientId, "Probeer een van volgende commando's",quickReplies);

    }

    private void sendHelpUnSub(String recipientId) throws MessengerApiException, MessengerIOException {
        final List<QuickReply> quickReplies = QuickReply.newListBuilder()
                .addTextQuickReply("Aboneer","sub").toList()
                .addTextQuickReply("Check status","checkstatus").toList()
                .addTextQuickReply("Tip","tip").toList()

                .build();

        this.sendClient.sendTextMessage(recipientId, "Probeer een van volgende commando's",quickReplies);


    }

    private void checkUserStatus(String senderId) {
        try {
            if (requestHandler.UserIsSub(senderId, requestHandler.GetSubscribers())) {
                this.sendClient.sendTextMessage(senderId, "U bent al reeds geregistreerd");
            } else {
                this.sendClient.sendTextMessage(senderId, "U bent nog niet geregistreerd");
                sendRegistrationMessage(senderId);
            }
        } catch (Exception e) {

        }
    }

    private void subcribeUser(String senderId) throws MessengerApiException, MessengerIOException {

        final QuickReply.ListBuilder quickReplies = QuickReply.newListBuilder();
        try {
            requestHandler.GetAgeGroups().forEach(ageGroup -> quickReplies
                    .addTextQuickReply(ageGroup.getGroup_name(), "postsub_"+Integer.toString(ageGroup.getId())).toList());

        } catch (Exception e) {

        }
        final List<QuickReply> list = quickReplies.build();

        this.sendClient.sendTextMessage(senderId, "Aan welke leeftijd geeft u les", list);
    }

    private void postSubcribeUser(String senderId, int age_id) throws MessengerApiException, MessengerIOException {
        try {

            Subscriber sub = new Subscriber(senderId, age_id);
            requestHandler.AddSubscriber(sub);
            this.sendClient.sendTextMessage(senderId, "U bent succesvol ingeschreven");
        } catch (Exception e) {

        }

    }
    private void unSub(String senderId) throws MessengerApiException, MessengerIOException {


        try {
            requestHandler.DeleteSubscriber(new Subscriber(senderId));
            this.sendClient.sendTextMessage(senderId, "U bent succesvol uitgeschreven.");

        } catch (Exception e) {

        }


    }

    private void sendGifMessage(String recipientId) throws MessengerApiException, MessengerIOException {
        this.sendClient.sendImageAttachment(recipientId, "https://media.giphy.com/media/11sBLVxNs7v6WA/giphy.gif");
    }

    private void sendRegistrationMessage(String recipientId) throws MessengerApiException, MessengerIOException {
        final List<QuickReply> quickReplies = QuickReply.newListBuilder()
                .addTextQuickReply("Ja!", "sub").toList()
                .addTextQuickReply("Nee, bedankt.", "DEVELOPER_DEFINED_PAYLOAD_FOR_PICKING_COMEDY").toList().build();

        this.sendClient.sendTextMessage(recipientId, "Wilt u zich registreren voor tips?", quickReplies);
    }

    private AttachmentMessageEventHandler newAttachmentMessageEventHandler() {
        return event -> {
            logger.debug("Received AttachmentMessageEvent: {}", event);

            final String messageId = event.getMid();
            final List<Attachment> attachments = event.getAttachments();
            final String senderId = event.getSender().getId();
            final Date timestamp = event.getTimestamp();

            logger.info("Received message '{}' with attachments from user '{}' at '{}':", messageId, senderId,
                    timestamp);

            attachments.forEach(attachment -> {
                final AttachmentType attachmentType = attachment.getType();
                final Payload payload = attachment.getPayload();

                String payloadAsString = null;
                if (payload.isBinaryPayload()) {
                    payloadAsString = payload.asBinaryPayload().getUrl();
                }
                if (payload.isLocationPayload()) {
                    payloadAsString = payload.asLocationPayload().getCoordinates().toString();
                }

                logger.info("Attachment of type '{}' with payload '{}'", attachmentType, payloadAsString);
            });
        };
    }

    private QuickReplyMessageEventHandler newQuickReplyMessageEventHandler() {
        return event -> {
            logger.debug("Received QuickReplyMessageEvent: {}", event);

            final String messageId = event.getMid();
            final String quickReplyPayload = event.getQuickReply().getPayload();
            final String senderId = event.getSender().getId();


            logger.info("Received quick reply for message '{}' with payload '{}'", messageId, quickReplyPayload);
            try {
                switch (quickReplyPayload.toLowerCase()) {


                    case "checkstatus":
                        checkUserStatus(senderId);
                        break;
                    case "postsub_0":
                        postSubcribeUser(senderId,0);
                        break;
                    case "postsub_1":
                        postSubcribeUser(senderId,1);
                        break;

                    case "sub":
                        subcribeUser(senderId);
                        break;
                    case "tip":
                        sendTip(senderId);
                        break;
                    case "uitschrijven":
                        unSub(senderId);
                        break;


                    default:



                }

            }catch (Exception e){

            }


        };
    }

    private PostbackEventHandler newPostbackEventHandler() {
        return event -> {
            logger.debug("Received PostbackEvent: {}", event);

            final String senderId = event.getSender().getId();
            final String recipientId = event.getRecipient().getId();
            final String payload = event.getPayload();
            final Date timestamp = event.getTimestamp();

            logger.info("Received postback for user '{}' and page '{}' with payload '{}' at '{}'", senderId,
                    recipientId, payload, timestamp);

        };
    }

    private AccountLinkingEventHandler newAccountLinkingEventHandler() {
        return event -> {
            logger.debug("Received AccountLinkingEvent: {}", event);

            final String senderId = event.getSender().getId();
            final AccountLinkingStatus accountLinkingStatus = event.getStatus();
            final String authorizationCode = event.getAuthorizationCode();

            logger.info("Received account linking event for user '{}' with status '{}' and auth code '{}'", senderId,
                    accountLinkingStatus, authorizationCode);
        };
    }

    private OptInEventHandler newOptInEventHandler() {
        return event -> {
            logger.debug("Received OptInEvent: {}", event);

            final String senderId = event.getSender().getId();
            final String recipientId = event.getRecipient().getId();
            final String passThroughParam = event.getRef();
            final Date timestamp = event.getTimestamp();

            logger.info("Received authentication for user '{}' and page '{}' with pass through param '{}' at '{}'",
                    senderId, recipientId, passThroughParam, timestamp);
        };
    }

    private EchoMessageEventHandler newEchoMessageEventHandler() {
        return event -> {
            logger.debug("Received EchoMessageEvent: {}", event);

            final String messageId = event.getMid();
            final String recipientId = event.getRecipient().getId();
            final String senderId = event.getSender().getId();
            final Date timestamp = event.getTimestamp();

            logger.info("Received echo for message '{}' that has been sent to recipient '{}' by sender '{}' at '{}'",
                    messageId, recipientId, senderId, timestamp);
        };
    }

    private MessageDeliveredEventHandler newMessageDeliveredEventHandler() {
        return event -> {
            logger.debug("Received MessageDeliveredEvent: {}", event);

            final List<String> messageIds = event.getMids();
            final Date watermark = event.getWatermark();
            final String senderId = event.getSender().getId();

            if (messageIds != null) {
                messageIds.forEach(messageId -> {
                    logger.info("Received delivery confirmation for message '{}'", messageId);
                });
            }

            logger.info("All messages before '{}' were delivered to user '{}'", watermark, senderId);
        };
    }

    private MessageReadEventHandler newMessageReadEventHandler() {
        return event -> {
            logger.debug("Received MessageReadEvent: {}", event);

            final Date watermark = event.getWatermark();
            final String senderId = event.getSender().getId();

            logger.info("All messages before '{}' were read by user '{}'", watermark, senderId);
        };
    }

    /**
     * This handler is called when either the message is unsupported or when the event handler for the actual event type
     * is not registered. In this showcase all event handlers are registered. Hence only in case of an
     * unsupported message the fallback event handler is called.
     */
    private FallbackEventHandler newFallbackEventHandler() {
        return event -> {
            logger.debug("Received FallbackEvent: {}", event);

            final String senderId = event.getSender().getId();
            logger.info("Received unsupported message from user '{}'", senderId);
        };
    }

    private void sendTextMessage(String recipientId, String text) {
        try {
            final Recipient recipient = Recipient.newBuilder().recipientId(recipientId).build();
            final NotificationType notificationType = NotificationType.REGULAR;
            final String metadata = "DEVELOPER_DEFINED_METADATA";

            this.sendClient.sendTextMessage(recipient, notificationType, text, metadata);
        } catch (MessengerApiException | MessengerIOException e) {
            handleSendException(e);
        }
    }

    private void handleSendException(Exception e) {
        logger.error("Message could not be sent. An unexpected error occurred.", e);
    }
}
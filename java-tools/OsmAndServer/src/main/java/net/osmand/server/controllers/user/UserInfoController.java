package net.osmand.server.controllers.user;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserInfoController {

	protected static final Log logger = LogFactory.getLog(UserInfoController.class);
	
    @RequestMapping("/u/info")
    public String index(java.security.Principal user) throws Exception {
		String pg = "Authorized page. Information about " +  user.getName() + ": ";
		if (user instanceof OAuth2Authentication) {
			OAuth2Authentication oAuth2Authentication = (OAuth2Authentication) user;
			Authentication authentication = oAuth2Authentication.getUserAuthentication();
			pg += authentication.getDetails();
			pg += authentication.getAuthorities();
		}
		
    	return pg;
    }

}
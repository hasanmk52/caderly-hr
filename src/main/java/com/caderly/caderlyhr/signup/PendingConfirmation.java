package com.caderly.caderlyhr.signup;

/**
 * What the confirmation page shows before the visitor presses the button: enough to recognise
 * their own sign-up, nothing that wasn't already in the email.
 */
public record PendingConfirmation(String companyName, String workspaceHost) {}
